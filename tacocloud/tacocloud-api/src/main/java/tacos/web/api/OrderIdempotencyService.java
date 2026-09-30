package tacos.web.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import lombok.AllArgsConstructor;
import lombok.Data;
import reactor.core.publisher.Mono;
import tacos.IdempotencyRecord;
import tacos.IdempotencyRecord.Status;
import tacos.TacoOrder;
import tacos.data.IdempotencyRecordRepository;
import tacos.data.OrderRepository;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

@Service
public class OrderIdempotencyService {

  static final int MIN_KEY_LENGTH = 8;
  static final int MAX_KEY_LENGTH = 128;
  private static final Pattern VALID_KEY = Pattern.compile(
      "[A-Za-z0-9._-]{" + MIN_KEY_LENGTH + "," + MAX_KEY_LENGTH + "}");

  private final IdempotencyRecordRepository records;
  private final OrderRepository orders;
  private final ReactiveMongoTemplate mongo;
  private final TransactionalOperator transactions;
  private final Clock clock;
  private final Duration retention;
  private final Duration inProgressTimeout;

  public OrderIdempotencyService(IdempotencyRecordRepository records,
      OrderRepository orders,ReactiveMongoTemplate mongo,
      TransactionalOperator transactions,Clock clock,
      @Value("${tacocloud.orders.idempotency.retention:24h}")
      Duration retention,
      @Value("${tacocloud.orders.idempotency.in-progress-timeout:2m}")
      Duration inProgressTimeout) {
    this.records = records;
    this.orders = orders;
    this.mongo = mongo;
    this.transactions = transactions;
    this.clock = clock;
    this.retention = retention;
    this.inProgressTimeout = inProgressTimeout;
  }

  public void validateKey(String key) {
    if (key == null || !VALID_KEY.matcher(key).matches()) {
      throw ApiException.badRequest(
          "INVALID_IDEMPOTENCY_KEY",
          "Idempotency-Key must contain 8 to 128 letters, digits, '.', '_' or '-'.");
    }
  }

  public Mono<PlacementResult> execute(String userId,String key,
      String requestHash,Function<String,Mono<TacoOrder>> placement) {
    validateKey(key);
    if (userId == null || userId.trim().isEmpty()) {
      return Mono.error(ApiException.badRequest(
          "IDEMPOTENCY_USER_REQUIRED","Authenticated user id is required."));
    }
    return attempt(userId,key,requestHash,placement);
  }

  private Mono<PlacementResult> attempt(String userId,String key,
      String requestHash,Function<String,Mono<TacoOrder>> placement) {
    return Mono.defer(() -> {
      Instant now = clock.instant();
      IdempotencyRecord record = new IdempotencyRecord(
          UUID.randomUUID().toString(),userId,key,requestHash,
          UUID.randomUUID().toString(),Status.IN_PROGRESS,
          now,now,now.plus(retention));

      return records.insert(record)
          .onErrorMap(DuplicateKeyException.class,
              error -> new ClaimAlreadyExistsException())
          .flatMap(claimed -> {
            Mono<PlacementResult> operation = Mono.defer(() ->
                placement.apply(claimed.getOrderId()))
              .flatMap(order -> complete(claimed)
                  .thenReturn(new PlacementResult(order,false)));
            return transactions.transactional(operation)
                .onErrorResume(error -> persistFailed(claimed)
                    .then(Mono.error(error)));
          })
          .onErrorResume(ClaimAlreadyExistsException.class,
              error -> resolveExisting(userId,key,requestHash,placement));
    });
  }

  private Mono<Void> complete(IdempotencyRecord record) {
    Instant now = clock.instant();
    record.setStatus(Status.COMPLETED);
    record.setUpdatedAt(now);
    record.setExpiresAt(now.plus(retention));
    return records.save(record).then();
  }

  private Mono<Void> persistFailed(IdempotencyRecord record) {
    Instant now = clock.instant();
    record.setStatus(Status.FAILED);
    record.setUpdatedAt(now);
    record.setExpiresAt(now.plus(retention));
    return records.save(record).then();
  }

  private Mono<PlacementResult> resolveExisting(String userId,String key,
      String requestHash,Function<String,Mono<TacoOrder>> placement) {
    return records.findByUserIdAndKey(userId,key)
        .flatMap(record -> {
          if (!requestHash.equals(record.getRequestHash())) {
            return Mono.error(ApiException.conflict(
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key was already used with another request."));
          }

          Instant now = clock.instant();
          if (record.getExpiresAt() != null
              && !record.getExpiresAt().isAfter(now)) {
            return reclaim(record,userId,key,requestHash,placement);
          }
          if (record.getStatus() == Status.COMPLETED) {
            return orders.findByIdAndUserId(record.getOrderId(),userId)
                .map(order -> new PlacementResult(order,true))
                .switchIfEmpty(Mono.error(ApiException.conflict(
                    "IDEMPOTENCY_RESULT_MISSING",
                    "The completed idempotency result is unavailable.")));
          }
          if (record.getStatus() == Status.FAILED
              || isAbandoned(record,now)) {
            return reclaim(record,userId,key,requestHash,placement);
          }
          return Mono.error(ApiException.conflict(
              "IDEMPOTENCY_IN_PROGRESS",
              "A request with this Idempotency-Key is still in progress."));
        })
        .switchIfEmpty(Mono.defer(() ->
            attempt(userId,key,requestHash,placement)));
  }

  private boolean isAbandoned(IdempotencyRecord record,Instant now) {
    return record.getStatus() == Status.IN_PROGRESS
        && record.getUpdatedAt() != null
        && !record.getUpdatedAt().isAfter(now.minus(inProgressTimeout));
  }

  private Mono<PlacementResult> reclaim(IdempotencyRecord record,
      String userId,String key,String requestHash,
      Function<String,Mono<TacoOrder>> placement) {
    Query exactRecord = Query.query(Criteria.where("_id").is(record.getId())
        .and("userId").is(userId)
        .and("key").is(key)
        .and("requestHash").is(requestHash)
        .and("status").is(record.getStatus())
        .and("updatedAt").is(record.getUpdatedAt()));
    return mongo.remove(exactRecord,IdempotencyRecord.class)
        .flatMap(result -> result.getDeletedCount() == 1
            ? attempt(userId,key,requestHash,placement)
            : resolveExisting(userId,key,requestHash,placement));
  }

  @Data
  @AllArgsConstructor
  public static class PlacementResult {
    private TacoOrder order;
    private boolean replayed;
  }

  private static final class ClaimAlreadyExistsException
      extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

}
