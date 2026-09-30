package tacos.actuator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class TacoMetricsTest {

  @Test
  public void shouldExposeCountersTimersGaugesAndOnlyBoundedTags() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TacoMetrics metrics = new TacoMetrics(registry);

    metrics.orderCreated(true);
    metrics.orderFailed();
    metrics.orderCancelled();
    metrics.stockRejected();
    metrics.outboxAdded();
    metrics.setOutboxBacklog(4);
    metrics.setKitchenQueue(3);
    metrics.recordKitchenLatency(Duration.ofMillis(25));
    Timer.Sample sample = metrics.startOrderPlacement();
    metrics.orderPlacementFinished(sample,"success");

    assertEquals(1.0,registry.get(TacoMetrics.ORDERS_CREATED).counter().count());
    assertEquals(1.0,registry.get(TacoMetrics.COUPONS_APPLIED).counter().count());
    assertEquals(4.0,registry.get(TacoMetrics.OUTBOX_BACKLOG).gauge().value());
    assertEquals(3.0,registry.get(TacoMetrics.KITCHEN_QUEUE).gauge().value());
    assertEquals(1L,registry.get(TacoMetrics.KITCHEN_LATENCY).timer().count());
    assertTrue(registry.get(TacoMetrics.KITCHEN_LATENCY).timer()
        .totalTime(TimeUnit.NANOSECONDS) > 0.0);
    assertEquals(1L,registry.get(TacoMetrics.ORDER_PLACEMENT)
        .tag("result","success").timer().count());

    Set<String> allowed = new HashSet<>(Arrays.asList("result","eventType"));
    Set<String> forbidden = new HashSet<>(Arrays.asList(
        "orderId","userId","correlationId","eventId","paymentMethodId",
        "email","username"));
    for (Meter meter : registry.getMeters()) {
      for (Tag tag : meter.getId().getTags()) {
        assertTrue(allowed.contains(tag.getKey()));
        assertFalse(forbidden.contains(tag.getKey()));
        assertFalse(tag.getValue().contains("sensitive@example.test"));
        assertFalse(tag.getValue().contains("tok_sensitive"));
      }
    }
  }
}
