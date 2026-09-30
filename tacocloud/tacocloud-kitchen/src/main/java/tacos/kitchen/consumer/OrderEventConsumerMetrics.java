package tacos.kitchen.consumer;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import tacos.messaging.OrderEvent;

@Component
public class OrderEventConsumerMetrics {

  private static final String METRIC = "tacocloud.kitchen.order.events";

  private final MeterRegistry registry;

  public OrderEventConsumerMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void processed(OrderEvent event) {
    increment("processed",event);
  }

  public void duplicate(OrderEvent event) {
    increment("duplicate",event);
  }

  public void retry(OrderEvent event) {
    increment("retry",event);
  }

  public void dlq(OrderEvent event) {
    increment("dlq",event);
  }

  private void increment(String result,OrderEvent event) {
    String eventType = event != null && event.getEventType() != null
        ? event.getEventType().name() : "UNKNOWN";
    registry.counter(METRIC,"result",result,"eventType",eventType)
        .increment();
  }
}
