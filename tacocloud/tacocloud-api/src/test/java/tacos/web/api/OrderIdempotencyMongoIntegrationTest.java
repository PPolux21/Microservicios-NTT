package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.embedded.EmbeddedMongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tacos.IdempotencyRecord;
import tacos.IdempotencyRecord.Status;
import tacos.Ingredient;
import tacos.InventoryReservation;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.TacoOrder.OrderItem;
import tacos.actuator.TacoMetrics;
import tacos.data.IdempotencyRecordRepository;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.outbox.OutboxEvent;
import tacos.data.outbox.OutboxEventRepository;
import tacos.web.api.OrderIdempotencyService.PlacementResult;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.outbox.MongoTransactionConfiguration;
import tacos.web.api.outbox.OrderOutboxService;

@Testcontainers
@SpringBootTest(
    classes=OrderIdempotencyMongoIntegrationTest.TestApplication.class,
    webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties="spring.data.mongodb.auto-index-creation=false")
public class OrderIdempotencyMongoIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

  @Container
  static final MongoDBContainer MONGO = new MongoDBContainer(
      DockerImageName.parse("mongo:6.0.14"));

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri",MONGO::getReplicaSetUrl);
  }

  @Autowired private OrderIdempotencyService idempotency;
  @Autowired private IdempotencyRecordRepository records;
  @Autowired private OrderRepository orders;
  @Autowired private IngredientRepository ingredients;
  @Autowired private OutboxEventRepository outbox;
  @Autowired private InventoryService inventory;
  @Autowired private OrderOutboxService orderOutbox;
  @Autowired private ReactiveMongoTemplate mongo;

  @BeforeEach
  public void clean() {
    ensureCollection(IdempotencyRecord.class)
        .then(ensureCollection(InventoryReservation.class))
        .then(ensureCollection(TacoOrder.class))
        .then(ensureCollection(OutboxEvent.class))
        .then(ensureCollection(Ingredient.class))
        .then(mongo.indexOps(IdempotencyRecord.class).ensureIndex(
            new Index()
                .on("userId",org.springframework.data.domain.Sort.Direction.ASC)
                .on("key",org.springframework.data.domain.Sort.Direction.ASC)
                .named("idempotency_user_key_unique").unique()))
        .then(mongo.indexOps(IdempotencyRecord.class).ensureIndex(
            new Index("expiresAt",
                org.springframework.data.domain.Sort.Direction.ASC)
                .named("idempotency_expiry_ttl").expire(Duration.ZERO)))
        .then(mongo.indexOps(OutboxEvent.class).ensureIndex(
            new Index("eventId",
                org.springframework.data.domain.Sort.Direction.ASC).unique()))
        .then(Mono.when(
            mongo.remove(new Query(),IdempotencyRecord.class),
            mongo.remove(new Query(),InventoryReservation.class),
            mongo.remove(new Query(),TacoOrder.class),
            mongo.remove(new Query(),OutboxEvent.class),
            mongo.remove(new Query(),Ingredient.class)))
        .block();
  }

  private Mono<Void> ensureCollection(Class<?> type) {
    return mongo.collectionExists(type)
        .flatMap(exists -> exists
            ? Mono.empty()
            : mongo.createCollection(type).then())
        .then();
  }

  @Test
  public void shouldReturnSameOrderWithoutRepeatingInventoryOrOutbox() {
    seedIngredient(10);
    AtomicInteger placements = new AtomicInteger();
    Function<String,Mono<TacoOrder>> placement = placement("USER-A",placements);

    StepVerifier.create(idempotency.execute(
        "USER-A","order-key-001","HASH-A",placement)
        .flatMap(first -> idempotency.execute(
            "USER-A","order-key-001","HASH-A",placement)
            .map(second -> java.util.Arrays.asList(first,second))))
        .assertNext(results -> {
          assertFalse(results.get(0).isReplayed());
          assertTrue(results.get(1).isReplayed());
          assertEquals(results.get(0).getOrder().getId(),
              results.get(1).getOrder().getId());
        })
        .verifyComplete();

    assertEffects(1,1,1,1,9);
    assertEquals(1,placements.get());
    IdempotencyRecord completed = records.findByUserIdAndKey(
        "USER-A","order-key-001").block();
    assertEquals(Status.COMPLETED,completed.getStatus());
    assertEquals(NOW.plus(Duration.ofHours(24)),completed.getExpiresAt());
  }

  @Test
  public void shouldAllowOnlyOneConcurrentPlacement() {
    seedIngredient(10);
    AtomicInteger placements = new AtomicInteger();
    Sinks.One<Void> claimReached = Sinks.one();
    Sinks.One<Void> releaseWinner = Sinks.one();
    Function<String,Mono<TacoOrder>> delayedPlacement = orderId -> {
      placements.incrementAndGet();
      claimReached.tryEmitEmpty();
      return releaseWinner.asMono().then(place("USER-A",orderId));
    };

    Mono<Signal<PlacementResult>> winner = idempotency.execute(
        "USER-A","order-key-002","HASH-A",delayedPlacement)
        .materialize().cache();
    Mono<Signal<PlacementResult>> concurrent = claimReached.asMono()
        .then(idempotency.execute(
            "USER-A","order-key-002","HASH-A",delayedPlacement))
        .materialize()
        .doOnNext(ignored -> releaseWinner.tryEmitEmpty())
        .cache();

    StepVerifier.create(Mono.zip(winner,concurrent))
        .assertNext(signals -> {
          assertTrue(signals.getT1().isOnNext());
          assertTrue(signals.getT2().isOnError());
          assertApiError(signals.getT2().getThrowable(),
              HttpStatus.CONFLICT,"IDEMPOTENCY_IN_PROGRESS");
        })
        .verifyComplete();

    assertEffects(1,1,1,1,9);
    assertEquals(1,placements.get());
  }

  @Test
  public void shouldRejectDifferentPayloadAndScopeSameKeyByUser() {
    seedIngredient(10);

    StepVerifier.create(idempotency.execute(
        "USER-A","shared-key-01","HASH-A",placement("USER-A",new AtomicInteger()))
        .then(idempotency.execute(
            "USER-A","shared-key-01","HASH-B",placement("USER-A",new AtomicInteger()))))
        .expectErrorSatisfies(error -> assertApiError(
            error,HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUSED"))
        .verify();

    assertEffects(1,1,1,1,9);

    StepVerifier.create(idempotency.execute(
        "USER-B","shared-key-01","HASH-B",placement("USER-B",new AtomicInteger())))
        .expectNextMatches(result -> !result.isReplayed())
        .verifyComplete();

    assertEffects(2,2,2,2,8);
  }

  @Test
  public void shouldRecoverFailedAbandonedAndExpiredRecordsWithoutSleep() {
    seedIngredient(10);
    Function<String,Mono<TacoOrder>> placement = placement(
        "USER-A",new AtomicInteger());

    saveRecord("recent","recent-key-01",Status.IN_PROGRESS,
        NOW.minusSeconds(30),NOW.plus(Duration.ofHours(24)));
    StepVerifier.create(idempotency.execute(
        "USER-A","recent-key-01","HASH-A",placement))
        .expectErrorSatisfies(error -> assertApiError(
            error,HttpStatus.CONFLICT,"IDEMPOTENCY_IN_PROGRESS"))
        .verify();

    saveRecord("stale","stale-key-001",Status.IN_PROGRESS,
        NOW.minus(Duration.ofMinutes(3)),NOW.plus(Duration.ofHours(24)));
    saveRecord("failed","failed-key-01",Status.FAILED,
        NOW.minusSeconds(1),NOW.plus(Duration.ofHours(24)));
    saveRecord("expired","expired-key-1",Status.COMPLETED,
        NOW.minus(Duration.ofHours(25)),NOW.minusSeconds(1));

    StepVerifier.create(Flux.concat(
        idempotency.execute("USER-A","stale-key-001","HASH-A",placement),
        idempotency.execute("USER-A","failed-key-01","HASH-A",placement),
        idempotency.execute("USER-A","expired-key-1","HASH-A",placement)))
        .expectNextCount(3)
        .verifyComplete();

    assertEffects(3,4,3,3,7);
  }

  @Test
  public void shouldRollbackInventoryAndPersistFailedBeforeCleanRetry() {
    seedIngredient(10);
    Function<String,Mono<TacoOrder>> failsAfterReservation = orderId ->
        ingredients.findById("FLTO")
            .flatMap(ingredient -> inventory.reserve(
                draft("USER-A",orderId,ingredient)))
            .then(Mono.error(new IllegalStateException("placement failed")));

    StepVerifier.create(idempotency.execute(
        "USER-A","failed-key-02","HASH-A",failsAfterReservation))
        .expectErrorMatches(error ->
            error instanceof IllegalStateException
                && "placement failed".equals(error.getMessage()))
        .verify();

    assertEffects(0,1,0,0,10);
    assertEquals(Status.FAILED,records.findByUserIdAndKey(
        "USER-A","failed-key-02").block().getStatus());

    StepVerifier.create(idempotency.execute(
        "USER-A","failed-key-02","HASH-A",
        placement("USER-A",new AtomicInteger())))
        .expectNextMatches(result -> !result.isReplayed())
        .verifyComplete();

    assertEffects(1,1,1,1,9);
    assertEquals(Status.COMPLETED,records.findByUserIdAndKey(
        "USER-A","failed-key-02").block().getStatus());
  }

  @Test
  public void shouldValidateKeysAndCreateRequiredMongoIndexes() {
    for (String invalid : new String[] {
        null,"","short","contains space","bad\nkey",
        String.join("",Collections.nCopies(129,"a"))}) {
      ApiException error = assertThrows(
          ApiException.class,() -> idempotency.validateKey(invalid));
      assertEquals(HttpStatus.BAD_REQUEST,error.getStatus());
      assertEquals("INVALID_IDEMPOTENCY_KEY",error.getCode());
    }

    List<Document> indexes = mongo.getCollection("idempotencyRecords")
        .flatMapMany(collection -> Flux.from(collection.listIndexes()))
        .collectList().block();
    assertTrue(indexes.stream().anyMatch(index ->
        "idempotency_user_key_unique".equals(index.getString("name"))
            && Boolean.TRUE.equals(index.getBoolean("unique"))
            && new Document("userId",1).append("key",1)
                .equals(index.get("key",Document.class))));
    assertTrue(indexes.stream().anyMatch(index ->
        "idempotency_expiry_ttl".equals(index.getString("name"))
            && Integer.valueOf(0).equals(index.get("expireAfterSeconds"))));
  }

  private Function<String,Mono<TacoOrder>> placement(
      String userId,AtomicInteger calls) {
    return orderId -> Mono.defer(() -> {
      calls.incrementAndGet();
      return place(userId,orderId);
    });
  }

  private Mono<TacoOrder> place(String userId,String orderId) {
    return ingredients.findById("FLTO")
        .flatMap(ingredient -> {
          TacoOrder order = draft(userId,orderId,ingredient);
          return inventory.reserve(order)
              .flatMap(ignored -> orderOutbox.saveCreated(order,"corr-tc34"));
        });
  }

  private TacoOrder draft(String userId,String orderId,Ingredient ingredient) {
    Taco taco = new Taco();
    taco.setName("Idempotent taco");
    taco.setIngredients(Collections.singletonList(ingredient));
    TacoOrder order = new TacoOrder();
    order.setId(orderId);
    order.setUserId(userId);
    order.setPlacedAt(Date.from(NOW));
    order.setStatus(TacoOrder.Status.CREATED);
    order.setItems(Collections.singletonList(new OrderItem(
        taco,1,new BigDecimal("10.00"),new BigDecimal("10.00"))));
    order.setTotal(new BigDecimal("10.00"));
    return order;
  }

  private void seedIngredient(int stock) {
    Ingredient ingredient = new Ingredient(
        "FLTO","Flour Tortilla",Ingredient.Type.WRAP,
        new BigDecimal("10.00"),true,stock,2);
    ingredients.save(ingredient).block();
  }

  private void saveRecord(String id,String key,Status status,
      Instant updatedAt,Instant expiresAt) {
    records.save(new IdempotencyRecord(
        id,"USER-A",key,"HASH-A","OLD-" + id,status,
        NOW.minus(Duration.ofHours(1)),updatedAt,expiresAt)).block();
  }

  private void assertEffects(long orderCount,long recordCount,
      long reservationCount,long outboxCount,int stock) {
    assertEquals(orderCount,orders.count().block());
    assertEquals(recordCount,records.count().block());
    assertEquals(reservationCount,
        mongo.count(new Query(),InventoryReservation.class).block());
    assertEquals(outboxCount,outbox.count().block());
    assertEquals(stock,ingredients.findById("FLTO").block().getStockOnHand());
  }

  private void assertApiError(Throwable error,HttpStatus status,String code) {
    assertTrue(error instanceof ApiException);
    ApiException apiError = (ApiException) error;
    assertEquals(status,apiError.getStatus());
    assertEquals(code,apiError.getCode());
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration(exclude=EmbeddedMongoAutoConfiguration.class)
  @EnableReactiveMongoRepositories(basePackages="tacos.data")
  @Import({MongoTransactionConfiguration.class,TestConfiguration.class})
  static class TestApplication {
  }

  static class TestConfiguration {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW,ZoneOffset.UTC);
    }

    @Bean
    TacoMetrics tacoMetrics() {
      return new TacoMetrics(new SimpleMeterRegistry());
    }

    @Bean
    InventoryService inventoryService(
        ReactiveMongoTemplate mongo,TacoMetrics metrics) {
      return new InventoryService(mongo,metrics);
    }

    @Bean
    OrderOutboxService orderOutboxService(OrderRepository orders,
        OutboxEventRepository outbox,TransactionalOperator transactions,
        Clock clock,TacoMetrics metrics) {
      return new OrderOutboxService(
          orders,outbox,transactions,clock,metrics);
    }

    @Bean
    OrderIdempotencyService orderIdempotencyService(
        IdempotencyRecordRepository records,OrderRepository orders,
        ReactiveMongoTemplate mongo,TransactionalOperator transactions,
        Clock clock) {
      return new OrderIdempotencyService(
          records,orders,mongo,transactions,clock,
          Duration.ofHours(24),Duration.ofMinutes(2));
    }
  }
}
