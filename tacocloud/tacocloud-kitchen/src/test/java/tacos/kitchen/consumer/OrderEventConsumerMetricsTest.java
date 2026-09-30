package tacos.kitchen.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderEventType;

public class OrderEventConsumerMetricsTest {

  @Test
  public void retriesMustNotCountAsMoreThanOneFinalDlqEvent() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OrderEventConsumerMetrics metrics = new OrderEventConsumerMetrics(registry);
    OrderEvent event = event();

    metrics.retry(event);
    metrics.retry(event);
    metrics.dlq(event);

    assertEquals(2.0,registry.get("tacocloud.kitchen.order.events")
        .tag("result","retry").tag("eventType","ORDER_CREATED")
        .counter().count());
    assertEquals(1.0,registry.get("tacocloud.kitchen.order.events")
        .tag("result","dlq").tag("eventType","ORDER_CREATED")
        .counter().count());

    Set<String> allowed = Set.of("result","eventType");
    Set<String> forbidden = Set.of(
        "orderId","userId","correlationId","eventId");
    for (Meter meter : registry.getMeters()) {
      for (Tag tag : meter.getId().getTags()) {
        assertTrue(allowed.contains(tag.getKey()));
        assertFalse(forbidden.contains(tag.getKey()));
      }
    }
  }

  private OrderEvent event() {
    return new OrderEvent(
        UUID.fromString("00000000-0000-0000-0000-000000000032"),
        OrderEventType.ORDER_CREATED,1,
        Instant.parse("2026-09-30T12:00:00Z"),"corr-tc32",
        new OrderEventPayload(
            "ORDER-TC32","CREATED",null,
            Instant.parse("2026-09-30T12:00:00Z"),
            Collections.emptyList(),null,null,null));
  }
}
