package tacos.web.api;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;

@Service
public class OrderService {

  private OrderRepository repo;
  private OrderMessagingService orderMessages;
  private EmailOrderService emailOrderService;

  public OrderService(
      OrderRepository repo,
      OrderMessagingService orderMessages,
      EmailOrderService emailOrderService) {

    this.repo = repo;
    this.orderMessages = orderMessages;
    this.emailOrderService = emailOrderService;
  }

  public Mono<TacoOrder> createOrderFromEmail(
      Mono<EmailOrder> emailOrder) {

    return emailOrderService
        .convertEmailOrderToDomainOrder(emailOrder)

        .flatMap(repo::save)

        .flatMap(savedOrder ->
            Mono.fromRunnable(() ->
                orderMessages.sendOrder(savedOrder))
                .thenReturn(savedOrder));
  }
}