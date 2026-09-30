package tacos.web.api.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.embedded.EmbeddedMongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.actuator.TacoMetrics;
import tacos.TacoOrder.Status;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.data.consumer.ProcessedEventRepository;
import tacos.data.outbox.OutboxEvent;
import tacos.data.outbox.OutboxEventRepository;
import tacos.data.outbox.OutboxStatus;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderEventType;
import tacos.messaging.OrderMessagingService;
import tacos.web.api.CorrelationIdWebFilter;
import tacos.web.api.mapper.OrderEventMapper;

@Testcontainers
@SpringBootTest(
    classes=OutboxMongoIntegrationTest.TestApplication.class,
    webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties="spring.data.mongodb.auto-index-creation=true")
public class OutboxMongoIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-09-29T18:00:00Z");

  @Container
  static final MongoDBContainer MONGO = new MongoDBContainer(
      DockerImageName.parse("mongo:6.0.14"));

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri",MONGO::getReplicaSetUrl);
  }

  @Autowired private OrderRepository orders;
  @Autowired private OutboxEventRepository outbox;
  @Autowired private OrderOutboxService orderOutbox;
  @Autowired private ReactiveMongoTemplate mongo;
  @Autowired private Clock clock;
  @Autowired private TacoMetrics metrics;

  @BeforeEach
  public void clean() {
    mongo.remove(new Query(),TacoOrder.class)
        .then(mongo.remove(new Query(),OutboxEvent.class))
        .block();
  }

  @Test
  public void shouldCommitOrderAndOutboxInOneRealMongoTransaction() {
    TacoOrder order = order("ORDER-COMMIT");

    StepVerifier.create(CorrelationIdWebFilter.currentCorrelationId()
        .flatMap(correlationId -> orderOutbox.saveCreated(order,correlationId))
        .contextWrite(context -> context.put(
            CorrelationIdWebFilter.CONTEXT_KEY,"corr-commit")))
        .expectNextMatches(saved -> "ORDER-COMMIT".equals(saved.getId()))
        .verifyComplete();

    StepVerifier.create(orders.findById("ORDER-COMMIT"))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(outbox.findAll().single())
        .assertNext(record -> {
          assertEquals(OutboxStatus.NEW,record.getStatus());
          assertEquals(OrderEventType.ORDER_CREATED,record.getEventType());
          assertEquals("ORDER-COMMIT",record.getEvent().getPayload().getOrderId());
          assertEquals(record.getEventId(),record.getEvent().getEventId());
          assertEquals("corr-commit",record.getEvent().getCorrelationId());
        })
        .verifyComplete();
  }

  @Test
  public void shouldRollbackOrderWhenOutboxInsertFails() {
    OrderEvent duplicated = event("ORDER-ROLLBACK",
        UUID.fromString("00000000-0000-0000-0000-000000000029"));
    OutboxEvent existing = OutboxEvent.pending(duplicated,NOW);

    StepVerifier.create(outbox.save(existing)
        .then(orderOutbox.save(order("ORDER-ROLLBACK"),duplicated)))
        .expectError()
        .verify();

    StepVerifier.create(orders.findById("ORDER-ROLLBACK"))
        .verifyComplete();
    StepVerifier.create(outbox.findAll().count())
        .expectNext(1L)
        .verifyComplete();
  }

  @Test
  public void shouldRetryBrokerFailureThenPublishWithoutLosingRecord() {
    StepVerifier.create(orderOutbox.saveCreated(
        order("ORDER-RETRY"),"corr-retry"))
        .expectNextCount(1)
        .verifyComplete();

    OrderMessagingService messages = Mockito.mock(OrderMessagingService.class);
    AtomicInteger calls = new AtomicInteger();
    doAnswer(invocation -> {
      if (calls.incrementAndGet() == 1) {
        throw new IllegalStateException("broker unavailable");
      }
      return null;
    }).when(messages).sendOrder(Mockito.any(OrderEvent.class));
    OutboxPublisher publisher = publisher(messages,5,"publisher-retry");

    StepVerifier.create(publisher.publishBatch().then(publisher.publishBatch()))
        .verifyComplete();

    StepVerifier.create(outbox.findAll().single())
        .assertNext(record -> {
          assertEquals(OutboxStatus.PUBLISHED,record.getStatus());
          assertEquals(2,record.getAttempts());
          assertNotNull(record.getPublishedAt());
          assertEquals(2,calls.get());
        })
        .verifyComplete();
  }

  @Test
  public void shouldMoveToFailedAfterConfiguredAttempts() {
    StepVerifier.create(orderOutbox.saveCreated(
        order("ORDER-FAILED"),"corr-failed"))
        .expectNextCount(1)
        .verifyComplete();

    OrderMessagingService messages = Mockito.mock(OrderMessagingService.class);
    doThrow(new IllegalStateException("broker remains down"))
        .when(messages).sendOrder(Mockito.any(OrderEvent.class));
    OutboxPublisher publisher = publisher(messages,2,"publisher-failed");

    StepVerifier.create(publisher.publishBatch().then(publisher.publishBatch()))
        .verifyComplete();

    StepVerifier.create(outbox.findAll().single())
        .assertNext(record -> {
          assertEquals(OutboxStatus.FAILED,record.getStatus());
          assertEquals(2,record.getAttempts());
          assertTrue(record.getLastError().contains("broker remains down"));
        })
        .verifyComplete();
  }

  @Test
  public void shouldPersistBackoffAndPublishNewEventAfterRestart() {
    StepVerifier.create(orderOutbox.saveCreated(
        order("ORDER-RESTART"),"corr-restart"))
        .expectNextCount(1)
        .verifyComplete();

    OrderMessagingService failing = Mockito.mock(OrderMessagingService.class);
    doThrow(new IllegalStateException("temporary outage"))
        .when(failing).sendOrder(Mockito.any(OrderEvent.class));
    OutboxProperties retryPolicy = new OutboxProperties();
    retryPolicy.setBatchSize(1);
    retryPolicy.setMaxAttempts(5);
    retryPolicy.setInitialBackoff(Duration.ofSeconds(5));
    OutboxPublisher beforeRestart = new OutboxPublisher(
        mongo,failing,retryPolicy,clock,metrics,"publisher-before-restart");

    StepVerifier.create(beforeRestart.publishBatch()).verifyComplete();
    StepVerifier.create(outbox.findAll().single())
        .assertNext(record -> {
          assertEquals(OutboxStatus.NEW,record.getStatus());
          assertEquals(NOW.plusSeconds(5),record.getNextAttemptAt());
          assertEquals(1,record.getAttempts());
        })
        .verifyComplete();

    OrderMessagingService recovered = Mockito.mock(OrderMessagingService.class);
    Clock afterBackoff = Clock.fixed(NOW.plusSeconds(6),ZoneOffset.UTC);
    OutboxPublisher afterRestart = new OutboxPublisher(
        mongo,recovered,retryPolicy,afterBackoff,metrics,
        "publisher-after-restart");
    StepVerifier.create(afterRestart.publishBatch()).verifyComplete();

    StepVerifier.create(outbox.findAll().single())
        .assertNext(record -> assertEquals(
            OutboxStatus.PUBLISHED,record.getStatus()))
        .verifyComplete();
  }

  @Test
  public void shouldClaimOnlyOnceConcurrentlyAndRecoverAbandonedClaim() {
    StepVerifier.create(orderOutbox.saveCreated(
        order("ORDER-CLAIM"),"corr-claim"))
        .expectNextCount(1)
        .verifyComplete();

    OrderMessagingService messages = Mockito.mock(OrderMessagingService.class);
    OutboxPublisher first = publisher(messages,5,"publisher-a");
    OutboxPublisher second = publisher(messages,5,"publisher-b");

    StepVerifier.create(Flux.merge(
        first.claimNext(NOW),second.claimNext(NOW)).collectList())
        .assertNext(claims -> assertEquals(1,claims.size()))
        .verifyComplete();

    StepVerifier.create(second.claimNext(NOW.plus(Duration.ofMinutes(3))))
        .assertNext(reclaimed -> {
          assertEquals("publisher-b",reclaimed.getClaimedBy());
          assertEquals(2,reclaimed.getAttempts());
        })
        .verifyComplete();
  }

  @Test
  public void shouldKeepDistinctEventIdsAndSafePersistedContract()
      throws Exception {
    TacoOrder order = order("ORDER-TWO-EVENTS");
    order.setDeliveryName("Sensitive Customer");
    order.setDeliveryStreet("Private Street 29");
    order.setDeliveryZip("99999");
    User user = new User("safe-test-user","secret-password","Customer",
        "Private Street 29","City","State","99999","555-0100",
        "private@example.test");
    user.setId("USER-SENSITIVE");
    order.setUser(user);
    OrderEvent first = OrderEventMapper.orderCreated(order,"corr-test");
    OrderEvent second = OrderEventMapper.orderCreated(order,"corr-test");

    StepVerifier.create(orderOutbox.save(order,first)
        .flatMap(saved -> orderOutbox.save(saved,second)))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(outbox.findAll().collectList())
        .assertNext(records -> {
          assertEquals(2,records.size());
          assertNotEquals(records.get(0).getEventId(),records.get(1).getEventId());
        })
        .verifyComplete();

    List<Document> stored = mongo.getCollection("outbox_events")
        .flatMapMany(collection -> Flux.from(collection.find()))
        .collectList()
        .block();
    String persistedJson = stored.toString().toLowerCase();
    assertFalse(persistedJson.contains("ccnumber"));
    assertFalse(persistedJson.contains("cccvv"));
    assertFalse(persistedJson.contains("password"));
    assertFalse(persistedJson.contains("paymenttoken"));
    assertFalse(persistedJson.contains("deliveryname"));
    assertFalse(persistedJson.contains("sensitive customer"));
    assertFalse(persistedJson.contains("secret-password"));
    assertFalse(persistedJson.contains("private street 29"));

    String contractJson = new ObjectMapper().findAndRegisterModules()
        .writeValueAsString(first);
    assertTrue(contractJson.contains("\"correlationId\":\"corr-test\""));
  }

  private OutboxPublisher publisher(OrderMessagingService messages,
      int maxAttempts,String id) {
    OutboxProperties properties = new OutboxProperties();
    properties.setBatchSize(10);
    properties.setMaxAttempts(maxAttempts);
    properties.setInitialBackoff(Duration.ZERO);
    properties.setClaimTimeout(Duration.ofMinutes(2));
    return new OutboxPublisher(mongo,messages,properties,clock,metrics,id);
  }

  private TacoOrder order(String id) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setStatus(Status.CREATED);
    order.setPlacedAt(java.util.Date.from(NOW));
    return order;
  }

  private OrderEvent event(String orderId,UUID eventId) {
    return new OrderEvent(eventId,OrderEventType.ORDER_CREATED,1,NOW,
        "corr-test",new OrderEventPayload(
            orderId,"CREATED",null,NOW,Collections.emptyList(),
            null,null,null));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration(exclude=EmbeddedMongoAutoConfiguration.class)
  @EnableReactiveMongoRepositories(
      basePackageClasses=OrderRepository.class,
      excludeFilters=@ComponentScan.Filter(
          type=FilterType.ASSIGNABLE_TYPE,
          classes=ProcessedEventRepository.class))
  @Import({MongoTransactionConfiguration.class,OrderOutboxService.class,
      TacoMetrics.class})
  static class TestApplication {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW,ZoneOffset.UTC);
    }
  }
}
