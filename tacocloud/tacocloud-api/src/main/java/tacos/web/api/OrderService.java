package tacos.web.api;

import java.util.Date;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.web.api.mapper.ApiMapper;
import tacos.web.api.mapper.ApiMapper.OrderCreateCommand;
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


  /*
   * TC-08
   * Creación de una orden desde un DTO.
   */
  public Mono<TacoOrder> createOrder(OrderCreateCommand command) {

    TacoOrder order = ApiMapper.toEntity(command);

    order.setPlacedAt(new Date());

    return repo.save(order)
      .flatMap(savedOrder ->
        Mono.fromRunnable(() ->
          orderMessages.sendOrder(savedOrder))
            .thenReturn(savedOrder));
  }


  /*
   * TC-07
   * Se conserva el flujo de órdenes
   * recibidas por correo.
   */
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