package tacos.web.api.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.reactive.TransactionalOperator;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.TacoOrder.Status;
import tacos.actuator.TacoMetrics;
import tacos.data.OrderRepository;
import tacos.data.outbox.OutboxEvent;
import tacos.data.outbox.OutboxEventRepository;

public class OrderOutboxServiceMetricsTest {

  @SuppressWarnings({"unchecked","rawtypes"})
  @Test
  public void shouldCountCreatedCouponAndBacklogOnlyAfterDurableOperation() {
    OrderRepository orders = Mockito.mock(OrderRepository.class);
    OutboxEventRepository outbox = Mockito.mock(OutboxEventRepository.class);
    TransactionalOperator transactions = Mockito.mock(TransactionalOperator.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TacoMetrics metrics = new TacoMetrics(registry);
    TacoOrder order = new TacoOrder();
    order.setId("ORDER-METRICS");
    order.setStatus(Status.CREATED);
    order.setPlacedAt(java.util.Date.from(Instant.parse("2026-09-30T12:00:00Z")));
    order.setAppliedCouponCode("PROMO10");

    when(orders.save(order)).thenReturn(Mono.just(order));
    when(outbox.save(any(OutboxEvent.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(transactions.transactional(any(Mono.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    OrderOutboxService service = new OrderOutboxService(
        orders,outbox,transactions,
        Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"),ZoneOffset.UTC),
        metrics);

    StepVerifier.create(service.saveCreated(order,"corr-metrics"))
        .expectNext(order)
        .verifyComplete();

    assertEquals(1.0,registry.get(TacoMetrics.ORDERS_CREATED).counter().count());
    assertEquals(1.0,registry.get(TacoMetrics.COUPONS_APPLIED).counter().count());
    assertEquals(1.0,registry.get(TacoMetrics.OUTBOX_BACKLOG).gauge().value());
    assertEquals(1.0,registry.get(TacoMetrics.KITCHEN_QUEUE).gauge().value());
  }
}
