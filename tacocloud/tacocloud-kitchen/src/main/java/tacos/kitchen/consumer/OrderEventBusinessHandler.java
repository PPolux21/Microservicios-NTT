package tacos.kitchen.consumer;

import reactor.core.publisher.Mono;
import tacos.messaging.OrderEvent;

public interface OrderEventBusinessHandler {

  Mono<Void> apply(OrderEvent event);
}
