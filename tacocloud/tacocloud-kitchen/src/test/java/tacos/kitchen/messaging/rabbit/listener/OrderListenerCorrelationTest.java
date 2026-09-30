package tacos.kitchen.messaging.rabbit.listener;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import reactor.core.publisher.Mono;
import tacos.kitchen.consumer.KitchenConsumerProperties;
import tacos.kitchen.consumer.OrderEventConsumerMetrics;
import tacos.kitchen.consumer.OrderEventConsumerService;
import tacos.kitchen.consumer.OrderEventProcessingResult;
import tacos.kitchen.messaging.rabbit.RabbitOrderDlqPublisher;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderEventType;

public class OrderListenerCorrelationTest {

  @Test
  public void shouldLogConsumerWorkWithEventCorrelationAndCleanMdc() {
    OrderEventConsumerService consumer = mock(OrderEventConsumerService.class);
    RabbitOrderDlqPublisher dlq = mock(RabbitOrderDlqPublisher.class);
    OrderEventConsumerMetrics metrics = mock(OrderEventConsumerMetrics.class);
    KitchenConsumerProperties properties = new KitchenConsumerProperties();
    properties.setMaxAttempts(1);
    OrderEvent event = event("consumer-correlation-31");
    when(consumer.process(event))
        .thenReturn(Mono.just(OrderEventProcessingResult.PROCESSED));

    Logger logger = (Logger) LoggerFactory.getLogger(OrderListener.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try {
      new OrderListener(consumer,properties,dlq,metrics)
          .receiveOrder(event).block();

      assertEquals(2,appender.list.size());
      assertTrue(appender.list.stream().allMatch(log ->
          "consumer-correlation-31".equals(
              log.getMDCPropertyMap().get("correlationId"))));
      assertNull(MDC.get("correlationId"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
      MDC.remove("correlationId");
    }
  }

  private OrderEvent event(String correlationId) {
    return new OrderEvent(
        UUID.fromString("00000000-0000-0000-0000-000000000031"),
        OrderEventType.ORDER_CREATED,1,
        Instant.parse("2026-09-30T08:00:00Z"),correlationId,
        new OrderEventPayload("ORDER-31","CREATED",null,
            Instant.parse("2026-09-30T07:59:00Z"),
            Collections.emptyList(),null,null,null));
  }
}
