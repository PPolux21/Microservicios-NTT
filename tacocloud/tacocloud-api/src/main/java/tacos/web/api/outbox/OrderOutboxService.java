package tacos.web.api.outbox;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.TacoOrder.Status;
import tacos.data.OrderRepository;
import tacos.data.outbox.OutboxEvent;
import tacos.data.outbox.OutboxEventRepository;
import tacos.messaging.OrderEvent;
import tacos.web.api.mapper.OrderEventMapper;

@Service
public class OrderOutboxService {

  private final OrderRepository orders;
  private final OutboxEventRepository outbox;
  private final TransactionalOperator transactions;
  private final Clock clock;

  public OrderOutboxService(OrderRepository orders,
      OutboxEventRepository outbox,TransactionalOperator transactions,
      Clock clock) {
    this.orders = orders;
    this.outbox = outbox;
    this.transactions = transactions;
    this.clock = clock;
  }

  public Mono<TacoOrder> saveCreated(TacoOrder order,String correlationId) {
    return save(order,saved ->
        OrderEventMapper.orderCreated(saved,correlationId));
  }

  public Mono<TacoOrder> saveStatusChanged(TacoOrder order,
      Status previousStatus,String correlationId,String reason) {
    return save(order,saved -> OrderEventMapper.statusChanged(
        saved,previousStatus,correlationId,reason));
  }

  public Mono<TacoOrder> save(TacoOrder order,OrderEvent event) {
    return save(order,ignored -> event);
  }

  private Mono<TacoOrder> save(TacoOrder order,
      java.util.function.Function<TacoOrder,OrderEvent> eventFactory) {
    Mono<TacoOrder> operation = orders.save(order)
        .flatMap(saved -> outbox.save(OutboxEvent.pending(
            eventFactory.apply(saved),clock.instant()))
            .thenReturn(saved));
    return transactions.transactional(operation);
  }
}
