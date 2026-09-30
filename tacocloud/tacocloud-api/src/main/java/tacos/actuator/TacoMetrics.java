package tacos.actuator;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class TacoMetrics {

  public static final String ORDERS_CREATED = "tacocloud.orders.created";
  public static final String ORDERS_FAILED = "tacocloud.orders.failed";
  public static final String ORDERS_CANCELLED = "tacocloud.orders.cancelled";
  public static final String COUPONS_APPLIED = "tacocloud.coupons.applied";
  public static final String INVENTORY_REJECTED = "tacocloud.inventory.rejected";
  public static final String ORDER_PLACEMENT = "tacocloud.orders.placement";
  public static final String KITCHEN_LATENCY = "tacocloud.kitchen.latency";
  public static final String OUTBOX_BACKLOG = "tacocloud.outbox.backlog";
  public static final String KITCHEN_QUEUE = "tacocloud.kitchen.queue";

  private final MeterRegistry registry;
  private final AtomicLong outboxBacklog = new AtomicLong();
  private final AtomicLong kitchenQueue = new AtomicLong();

  public TacoMetrics(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder(OUTBOX_BACKLOG,outboxBacklog,AtomicLong::get)
        .description("Outbox events in NEW or PUBLISHING state")
        .baseUnit("events")
        .register(registry);
    Gauge.builder(KITCHEN_QUEUE,kitchenQueue,AtomicLong::get)
        .description("Orders waiting in CREATED state for kitchen")
        .baseUnit("orders")
        .register(registry);
  }

  public Timer.Sample startOrderPlacement() {
    return Timer.start(registry);
  }

  public void orderPlacementFinished(Timer.Sample sample,String result) {
    sample.stop(Timer.builder(ORDER_PLACEMENT)
        .description("Local order and outbox placement latency")
        .tag("result",result)
        .register(registry));
  }

  public void orderCreated(boolean couponApplied) {
    registry.counter(ORDERS_CREATED).increment();
    if (couponApplied) {
      registry.counter(COUPONS_APPLIED).increment();
    }
    kitchenQueue.incrementAndGet();
  }

  public void orderFailed() {
    registry.counter(ORDERS_FAILED).increment();
  }

  public void orderCancelled() {
    registry.counter(ORDERS_CANCELLED).increment();
  }

  public void stockRejected() {
    registry.counter(INVENTORY_REJECTED).increment();
  }

  public void recordKitchenLatency(Duration duration) {
    if (duration != null && !duration.isNegative()) {
      registry.timer(KITCHEN_LATENCY).record(duration);
    }
  }

  public void outboxAdded() {
    outboxBacklog.incrementAndGet();
  }

  public void setOutboxBacklog(long pending) {
    outboxBacklog.set(Math.max(0L,pending));
  }

  public void kitchenOrderLeftQueue() {
    kitchenQueue.updateAndGet(current -> Math.max(0L,current - 1L));
  }

  public void setKitchenQueue(long queued) {
    kitchenQueue.set(Math.max(0L,queued));
  }
}
