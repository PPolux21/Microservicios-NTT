package tacos.web.api.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.actuator.TacoMetrics;
import tacos.data.outbox.OutboxEvent;
import tacos.data.outbox.OutboxStatus;
import tacos.messaging.OrderMessagingService;

@Component
public class OutboxPublisher {

  private final ReactiveMongoTemplate mongo;
  private final OrderMessagingService messages;
  private final OutboxProperties properties;
  private final Clock clock;
  private final String publisherId;
  private final TacoMetrics metrics;

  @Autowired
  public OutboxPublisher(ReactiveMongoTemplate mongo,
      OrderMessagingService messages,OutboxProperties properties,Clock clock,
      TacoMetrics metrics) {
    this(mongo,messages,properties,clock,metrics,UUID.randomUUID().toString());
  }

  OutboxPublisher(ReactiveMongoTemplate mongo,
      OrderMessagingService messages,OutboxProperties properties,Clock clock,
      TacoMetrics metrics,String publisherId) {
    this.mongo = mongo;
    this.messages = messages;
    this.properties = properties;
    this.clock = clock;
    this.metrics = metrics;
    this.publisherId = publisherId;
  }

  public Mono<Void> publishBatch() {
    return refreshBacklog().thenMany(Flux.range(0,properties.getBatchSize())
        .concatMap(ignored -> claimNext(clock.instant()))
        .concatMap(this::publishClaimed))
        .then(refreshBacklog());
  }

  private Mono<Void> refreshBacklog() {
    Query pending = Query.query(Criteria.where("status").in(
        OutboxStatus.NEW,OutboxStatus.PUBLISHING));
    return mongo.count(pending,OutboxEvent.class)
        .doOnNext(metrics::setOutboxBacklog)
        .then();
  }

  public Mono<OutboxEvent> claimNext(Instant now) {
    Instant abandonedBefore = now.minus(properties.getClaimTimeout());
    Criteria eligible = new Criteria().orOperator(
        Criteria.where("status").is(OutboxStatus.NEW)
            .and("nextAttemptAt").lte(now),
        Criteria.where("status").is(OutboxStatus.PUBLISHING)
            .and("claimedAt").lte(abandonedBefore));
    Query query = Query.query(eligible)
        .with(Sort.by(Sort.Direction.ASC,"createdAt","id"));
    Update claim = new Update()
        .set("status",OutboxStatus.PUBLISHING)
        .set("claimedAt",now)
        .set("claimedBy",publisherId)
        .set("updatedAt",now)
        .inc("attempts",1);
    return mongo.findAndModify(query,claim,
        FindAndModifyOptions.options().returnNew(true),OutboxEvent.class);
  }

  private Mono<Void> publishClaimed(OutboxEvent record) {
    return Mono.fromRunnable(() -> messages.sendOrder(record.getEvent()))
        .then(markPublished(record,clock.instant()))
        .onErrorResume(error -> markFailedAttempt(
            record,error,clock.instant()));
  }

  private Mono<Void> markPublished(OutboxEvent record,Instant now) {
    Query ownedClaim = ownedClaim(record);
    Update published = new Update()
        .set("status",OutboxStatus.PUBLISHED)
        .set("publishedAt",now)
        .set("updatedAt",now)
        .unset("nextAttemptAt")
        .unset("lastError");
    return mongo.updateFirst(ownedClaim,published,OutboxEvent.class).then();
  }

  private Mono<Void> markFailedAttempt(OutboxEvent record,Throwable error,
      Instant now) {
    boolean exhausted = record.getAttempts() >= properties.getMaxAttempts();
    Update failed = new Update()
        .set("status",exhausted ? OutboxStatus.FAILED : OutboxStatus.NEW)
        .set("updatedAt",now)
        .set("lastError",safeError(error))
        .unset("claimedAt")
        .unset("claimedBy");
    if (exhausted) {
      failed.unset("nextAttemptAt");
    } else {
      failed.set("nextAttemptAt",now.plus(backoff(record.getAttempts())));
    }
    return mongo.updateFirst(ownedClaim(record),failed,OutboxEvent.class).then();
  }

  private Query ownedClaim(OutboxEvent record) {
    return Query.query(Criteria.where("id").is(record.getId())
        .and("status").is(OutboxStatus.PUBLISHING)
        .and("claimedBy").is(publisherId));
  }

  private Duration backoff(int attempts) {
    long multiplier = 1L << Math.min(Math.max(attempts - 1,0),30);
    Duration candidate;
    try {
      candidate = properties.getInitialBackoff().multipliedBy(multiplier);
    } catch (ArithmeticException overflow) {
      candidate = properties.getMaxBackoff();
    }
    return candidate.compareTo(properties.getMaxBackoff()) > 0
        ? properties.getMaxBackoff() : candidate;
  }

  private String safeError(Throwable error) {
    String message = error.getClass().getSimpleName() + ": "
        + (error.getMessage() != null ? error.getMessage() : "no message");
    return message.length() <= 1000 ? message : message.substring(0,1000);
  }
}
