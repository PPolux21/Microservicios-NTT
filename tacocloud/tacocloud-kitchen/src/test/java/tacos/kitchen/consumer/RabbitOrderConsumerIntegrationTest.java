package tacos.kitchen.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.TacoOrder.Status;
import tacos.data.OrderRepository;
import tacos.data.consumer.ProcessedEvent;
import tacos.data.consumer.ProcessedEventRepository;
import tacos.data.outbox.OutboxEventRepository;
import tacos.kitchen.TacoKitchenApplication;
import tacos.kitchen.messaging.rabbit.RabbitOrderDlqPublisher;
import tacos.kitchen.messaging.rabbit.RabbitOrderDlqReplayService;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderEventType;

@Testcontainers
@SpringBootTest(
    classes={TacoKitchenApplication.class,
        RabbitOrderConsumerIntegrationTest.FaultInjectingConfiguration.class},
    webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.mongo.embedded."
            + "EmbeddedMongoAutoConfiguration",
        "tacocloud.messaging.transport=rabbit",
        "tacocloud.messaging.rabbit.destination=tc30.orders",
        "tacocloud.messaging.rabbit.dead-letter-exchange=tc30.orders.dlx",
        "tacocloud.messaging.rabbit.dead-letter-routing-key=tc30.orders.dead",
        "tacocloud.messaging.rabbit.dead-letter-queue=tc30.orders.dlq",
        "tacocloud.kitchen.consumer.max-attempts=3",
        "tacocloud.kitchen.consumer.backoff=10ms",
        "tacocloud.kitchen.consumer.concurrency=2"})
class RabbitOrderConsumerIntegrationTest {

  private static final String DESTINATION = "tc30.orders";
  private static final String DLQ = "tc30.orders.dlq";

  @Container
  static final MongoDBContainer MONGO =
      new MongoDBContainer("mongo:6.0.14");

  @Container
  static final RabbitMQContainer RABBIT =
      new RabbitMQContainer("rabbitmq:3.12-alpine");

  @DynamicPropertySource
  static void brokerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri",
        () -> MONGO.getReplicaSetUrl("tacocloud"));
    registry.add("spring.rabbitmq.host",RABBIT::getHost);
    registry.add("spring.rabbitmq.port",RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username",RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password",RABBIT::getAdminPassword);
  }

  @Autowired private OrderRepository orders;
  @Autowired private ProcessedEventRepository processedEvents;
  @Autowired private OutboxEventRepository outboxEvents;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin rabbitAdmin;
  @Autowired private RabbitOrderDlqPublisher dlqPublisher;
  @Autowired private RabbitOrderDlqReplayService replay;
  @Autowired private MeterRegistry metrics;
  @Autowired private FaultInjectingHandler testHandler;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void cleanState() {
    processedEvents.deleteAll().then(orders.deleteAll())
        .then(outboxEvents.deleteAll()).block();
    rabbitAdmin.purgeQueue(DESTINATION,true);
    rabbitAdmin.purgeQueue(DLQ,true);
    testHandler.reset();
  }

  @Test
  void duplicateRedeliveryCommitsOneEffectAndOneProcessedEvent() {
    seed("ORDER-DUPLICATE",Status.CREATED);
    OrderEvent event = event(UUID.randomUUID(),"ORDER-DUPLICATE",
        OrderEventType.ORDER_CREATED,"CREATED",1,"corr-duplicate");

    double duplicateBefore = metric("duplicate",OrderEventType.ORDER_CREATED);
    double processedBefore = metric("processed",OrderEventType.ORDER_CREATED);
    rabbit.convertAndSend(DESTINATION,event);
    await(() -> processedEvents.findByEventId(event.getEventId()).block()
        != null);
    rabbit.convertAndSend(DESTINATION,event);
    await(() -> metric("duplicate",OrderEventType.ORDER_CREATED)
        > duplicateBefore);
    await(() -> testHandler.attempts("corr-duplicate") >= 2);

    TacoOrder saved = orders.findById("ORDER-DUPLICATE").block();
    assertThat(saved.getStatus()).isEqualTo(Status.ACCEPTED);
    assertThat(saved.getStatusHistory()).hasSize(1);
    assertThat(processedEvents.count().block()).isEqualTo(1);
    assertThat(outboxEvents.count().block()).isEqualTo(1);
    assertThat(metric("processed",OrderEventType.ORDER_CREATED))
        .isEqualTo(processedBefore + 1);
  }

  @Test
  void concurrentDuplicateDeliveriesStillApplyBusinessOnce() {
    seed("ORDER-CONCURRENT",Status.CREATED);
    OrderEvent event = event(UUID.randomUUID(),"ORDER-CONCURRENT",
        OrderEventType.ORDER_CREATED,"CREATED",1,"corr-concurrent");

    double duplicateBefore = metric("duplicate",OrderEventType.ORDER_CREATED);
    rabbit.convertAndSend(DESTINATION,event);
    rabbit.convertAndSend(DESTINATION,event);

    await(() -> processedEvents.findByEventId(event.getEventId()).block()
        != null);
    await(() -> metric("duplicate",OrderEventType.ORDER_CREATED)
        > duplicateBefore);
    await(() -> testHandler.attempts("corr-concurrent") >= 2);
    TacoOrder saved = orders.findById("ORDER-CONCURRENT").block();
    assertThat(saved.getStatus()).isEqualTo(Status.ACCEPTED);
    assertThat(saved.getStatusHistory()).hasSize(1);
    assertThat(processedEvents.count().block()).isEqualTo(1);
  }

  @Test
  void distinctEventIdsForSameOrderAreBothProcessed() {
    seed("ORDER-TWO-EVENTS",Status.CREATED);
    OrderEvent created = event(UUID.randomUUID(),"ORDER-TWO-EVENTS",
        OrderEventType.ORDER_CREATED,"CREATED",1,"corr-created");
    OrderEvent preparing = event(UUID.randomUUID(),"ORDER-TWO-EVENTS",
        OrderEventType.STATUS_CHANGED,"PREPARING",1,"corr-preparing");

    rabbit.convertAndSend(DESTINATION,created);
    await(() -> processedEvents.findByEventId(created.getEventId()).block()
        != null);
    rabbit.convertAndSend(DESTINATION,preparing);
    await(() -> processedEvents.findByEventId(preparing.getEventId()).block()
        != null);

    assertThat(processedEvents.count().block()).isEqualTo(2);
    assertThat(orders.findById("ORDER-TWO-EVENTS").block().getStatus())
        .isEqualTo(Status.PREPARING);
  }

  @Test
  void transientFailureRetriesConfiguredAttemptsThenSucceeds() {
    seed("ORDER-RETRY",Status.CREATED);
    OrderEvent event = event(UUID.randomUUID(),"ORDER-RETRY",
        OrderEventType.ORDER_CREATED,"CREATED",1,"retry-success");

    rabbit.convertAndSend(DESTINATION,event);
    await(() -> processedEvents.findByEventId(event.getEventId()).block()
        != null);

    assertThat(testHandler.attempts("retry-success")).isEqualTo(3);
    assertThat(orders.findById("ORDER-RETRY").block().getStatus())
        .isEqualTo(Status.ACCEPTED);
    assertThat(metric("retry",OrderEventType.ORDER_CREATED)).isGreaterThanOrEqualTo(2);
  }

  @Test
  void exhaustedTransientFailureGoesToSpecificDlqWithSafeHeaders() {
    seed("ORDER-EXHAUSTED",Status.CREATED);
    OrderEvent event = event(UUID.randomUUID(),"ORDER-EXHAUSTED",
        OrderEventType.ORDER_CREATED,"CREATED",1,"retry-exhausted");

    double dlqBefore = metric("dlq",OrderEventType.ORDER_CREATED);
    rabbit.convertAndSend(DESTINATION,event);
    Message dead = receiveDlq();

    assertThat(testHandler.attempts("retry-exhausted")).isEqualTo(3);
    assertThat(dead.getMessageProperties().getHeaders())
        .containsEntry(RabbitOrderDlqPublisher.CAUSE_HEADER,
            "TRANSIENT_RETRIES_EXHAUSTED")
        .containsEntry(RabbitOrderDlqPublisher.CORRELATION_HEADER,
            "retry-exhausted")
        .containsEntry(RabbitOrderDlqPublisher.EVENT_ID_HEADER,
            event.getEventId().toString())
        .containsEntry(RabbitOrderDlqPublisher.ORIGINAL_DESTINATION_HEADER,
            DESTINATION);
    String serialized = new String(dead.getBody(),StandardCharsets.UTF_8)
        .toLowerCase();
    assertThat(serialized).doesNotContain(
        "ccnumber","cvv","password","paymenttoken","user");
    assertThat(processedEvents.count().block()).isZero();
    assertThat(orders.findById("ORDER-EXHAUSTED").block().getStatus())
        .isEqualTo(Status.CREATED);
    assertThat(metric("dlq",OrderEventType.ORDER_CREATED))
        .isEqualTo(dlqBefore + 1);
  }

  @Test
  void permanentUnsupportedVersionGoesDirectlyToDlq() {
    seed("ORDER-VERSION",Status.CREATED);
    OrderEvent event = event(UUID.randomUUID(),"ORDER-VERSION",
        OrderEventType.ORDER_CREATED,"CREATED",999,"corr-version");

    rabbit.convertAndSend(DESTINATION,event);
    Message dead = receiveDlq();

    assertThat(testHandler.attempts("corr-version")).isZero();
    assertThat(dead.getMessageProperties().getHeaders())
        .containsEntry(RabbitOrderDlqPublisher.CAUSE_HEADER,
            "UNSUPPORTED_EVENT_VERSION")
        .containsEntry(RabbitOrderDlqPublisher.CORRELATION_HEADER,
            "corr-version");
    assertThat(processedEvents.count().block()).isZero();
  }

  @Test
  void unknownEventTypeIsRejectedByConverterAndDeadLettered() throws Exception {
    UUID eventId = UUID.randomUUID();
    String json = objectMapper.writeValueAsString(event(
        eventId,"ORDER-UNKNOWN",OrderEventType.ORDER_CREATED,
        "CREATED",1,"corr-unknown"))
        .replace("ORDER_CREATED","FUTURE_EVENT");
    Message raw = MessageBuilder.withBody(
            json.getBytes(StandardCharsets.UTF_8))
        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
        .setHeader("__TypeId__",OrderEvent.class.getName())
        .setHeader(RabbitOrderDlqPublisher.CORRELATION_HEADER,
            "corr-unknown")
        .setHeader(RabbitOrderDlqPublisher.EVENT_ID_HEADER,eventId.toString())
        .build();

    rabbit.send("",DESTINATION,raw);
    Message dead = receiveDlq();

    assertThat(dead.getMessageProperties().getHeaders())
        .containsEntry(RabbitOrderDlqPublisher.CORRELATION_HEADER,
            "corr-unknown")
        .containsEntry(RabbitOrderDlqPublisher.EVENT_ID_HEADER,
            eventId.toString())
        .containsKey("x-first-death-reason");
    assertThat(processedEvents.count().block()).isZero();
  }

  @Test
  void businessEffectRollsBackWhenProcessedMarkerCannotCommit() {
    seed("ORDER-ROLLBACK",Status.CREATED);
    OrderEvent event = event(UUID.randomUUID(),"ORDER-ROLLBACK",
        OrderEventType.ORDER_CREATED,"CREATED",1,"rollback-after-effect");

    rabbit.convertAndSend(DESTINATION,event);
    Message dead = receiveDlq();

    assertThat(dead.getMessageProperties().getHeaders())
        .containsEntry(RabbitOrderDlqPublisher.CAUSE_HEADER,
            "TEST_AFTER_EFFECT_FAILURE");
    assertThat(orders.findById("ORDER-ROLLBACK").block().getStatus())
        .isEqualTo(Status.CREATED);
    assertThat(processedEvents.count().block()).isZero();
    assertThat(outboxEvents.count().block()).isZero();
  }

  @Test
  void replayPreservesEventIdAndDoesNotRepeatCommittedEffect() {
    seed("ORDER-REPLAY",Status.CREATED);
    OrderEvent event = event(UUID.randomUUID(),"ORDER-REPLAY",
        OrderEventType.ORDER_CREATED,"CREATED",1,"corr-replay");
    rabbit.convertAndSend(DESTINATION,event);
    await(() -> processedEvents.findByEventId(event.getEventId()).block()
        != null);
    double duplicateBefore = metric("duplicate",OrderEventType.ORDER_CREATED);

    StepVerifier.create(dlqPublisher.publish(event,
        new PermanentOrderEventException("REPLAY_TEST","test")))
        .verifyComplete();
    await(() -> queueDepth(DLQ) == 1);
    StepVerifier.create(replay.replayOne()).expectNext(true).verifyComplete();
    await(() -> metric("duplicate",OrderEventType.ORDER_CREATED)
        > duplicateBefore);

    TacoOrder saved = orders.findById("ORDER-REPLAY").block();
    assertThat(saved.getStatusHistory()).hasSize(1);
    assertThat(processedEvents.findByEventId(event.getEventId()).block())
        .isNotNull();
  }

  @Test
  void mongoUniqueIndexRejectsDuplicateEventId() {
    OrderEvent event = event(UUID.randomUUID(),"ORDER-INDEX",
        OrderEventType.ORDER_CREATED,"CREATED",1,"corr-index");
    processedEvents.save(ProcessedEvent.completed(event,Instant.now())).block();

    assertThatThrownBy(() -> processedEvents.save(
        ProcessedEvent.completed(event,Instant.now())).block())
        .hasRootCauseInstanceOf(com.mongodb.MongoWriteException.class);
  }

  private void seed(String id,Status status) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setStatus(status);
    orders.save(order).block();
  }

  private OrderEvent event(UUID eventId,String orderId,OrderEventType type,
      String status,int version,String correlationId) {
    return new OrderEvent(eventId,type,version,
        Instant.parse("2026-09-30T06:00:00Z"),correlationId,
        new OrderEventPayload(orderId,status,null,
            Instant.parse("2026-09-30T05:59:00Z"),
            Collections.emptyList(),null,null,null));
  }

  private Message receiveDlq() {
    final Message[] result = new Message[1];
    await(() -> {
      result[0] = rabbit.receive(DLQ,100);
      return result[0] != null;
    });
    return result[0];
  }

  private double metric(String result,OrderEventType type) {
    io.micrometer.core.instrument.Counter counter = metrics.find(
        "tacocloud.kitchen.order.events")
        .tags("result",result,"eventType",type.name()).counter();
    return counter != null ? counter.count() : 0;
  }

  private long queueDepth(String queue) {
    Long count = rabbit.execute(channel -> channel.messageCount(queue));
    return count != null ? count : 0;
  }

  private void await(BooleanSupplier condition) {
    StepVerifier.create(Flux.interval(Duration.ZERO,Duration.ofMillis(25))
        .publishOn(Schedulers.boundedElastic())
        .filter(ignored -> condition.getAsBoolean())
        .next()
        .timeout(Duration.ofSeconds(15)))
        .expectNextCount(1)
        .verifyComplete();
  }

  @TestConfiguration
  static class FaultInjectingConfiguration {

    @Bean
    @Primary
    FaultInjectingHandler faultInjectingHandler(
        WorkflowOrderEventBusinessHandler delegate) {
      return new FaultInjectingHandler(delegate);
    }
  }

  static class FaultInjectingHandler implements OrderEventBusinessHandler {

    private final WorkflowOrderEventBusinessHandler delegate;
    private final Map<String,AtomicInteger> attempts =
        new ConcurrentHashMap<>();

    FaultInjectingHandler(WorkflowOrderEventBusinessHandler delegate) {
      this.delegate = delegate;
    }

    @Override
    public Mono<Void> apply(OrderEvent event) {
      String correlation = event.getCorrelationId();
      int attempt = attempts.computeIfAbsent(
          correlation,ignored -> new AtomicInteger()).incrementAndGet();
      if ("retry-success".equals(correlation) && attempt < 3) {
        return Mono.error(new TransientOrderEventException(
            "Temporary test failure",null));
      }
      if ("retry-exhausted".equals(correlation)) {
        return Mono.error(new TransientOrderEventException(
            "Persistent temporary test failure",null));
      }
      if ("rollback-after-effect".equals(correlation)) {
        return delegate.apply(event).then(Mono.error(
            new PermanentOrderEventException(
                "TEST_AFTER_EFFECT_FAILURE","Failure after effect")));
      }
      return delegate.apply(event);
    }

    int attempts(String correlationId) {
      AtomicInteger count = attempts.get(correlationId);
      return count != null ? count.get() : 0;
    }

    void reset() {
      attempts.clear();
    }
  }
}
