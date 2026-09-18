package tacos.web.api;

import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;

@RestController
@RequestMapping(path="/api/orders",
                produces="application/json")
@CrossOrigin(origins="http://localhost:8080")
public class OrderApiController {

  private OrderRepository repo;
  private OrderMessagingService orderMessages;
  private EmailOrderService emailOrderService;

  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            EmailOrderService emailOrderService) {
    this.repo = repo;
    this.orderMessages = orderMessages;
    this.emailOrderService = emailOrderService;
  }

  @GetMapping(produces="application/json")
  public Flux<TacoOrder> allOrders() {
    return repo.findAll();
  }

//  @PostMapping(consumes="application/json")
//  @ResponseStatus(HttpStatus.CREATED)
//  public Mono<Order> postOrder(@RequestBody Mono<Order> order) {
//    order.subscribe(orderMessages::sendOrder); // TODO: not ideal...work into reactive flow below
//    return order
//        .flatMap(repo::save);
//  }

  @PostMapping(consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<TacoOrder> postOrder(@RequestBody TacoOrder order) {
    orderMessages.sendOrder(order);
    return repo.save(order);
  }

  @PostMapping(path="fromEmail", consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<TacoOrder> postOrderFromEmail(@RequestBody Mono<EmailOrder> emailOrder) {
    Mono<TacoOrder> order = emailOrderService.convertEmailOrderToDomainOrder(emailOrder);
    order.subscribe(orderMessages::sendOrder); // TODO: not ideal...work into reactive flow below
    return order
        .flatMap(repo::save);
  }

  @PutMapping(path="/{orderId}", consumes="application/json")
  public Mono<ResponseEntity<TacoOrder>> putOrder(@PathVariable("orderId") String orderId,
    @RequestBody OrderDeliveryRequest request, Authentication authentication) {

    if (request.hasUnsupportedFields()) {
      return Mono.just(ResponseEntity.badRequest().build());
    }

    return repo.findById(orderId)
      .flatMap(existingOrder -> {

        if (!canModify(existingOrder, authentication)) {
          return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).<TacoOrder>build());
        }

        existingOrder.setDeliveryName(request.getDeliveryName());

        existingOrder.setDeliveryStreet(request.getDeliveryStreet());

        existingOrder.setDeliveryCity(request.getDeliveryCity());

        existingOrder.setDeliveryState(request.getDeliveryState());

        existingOrder.setDeliveryZip(request.getDeliveryZip());

        return repo.save(existingOrder).map(ResponseEntity::ok);
      })
      .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  @PatchMapping(path = "/{orderId}",consumes = "application/json")
  public Mono<ResponseEntity<TacoOrder>> patchOrder(@PathVariable("orderId") String orderId,
      @RequestBody OrderDeliveryRequest patch,Authentication authentication) {

    if (patch.hasUnsupportedFields()) {
      return Mono.just(ResponseEntity.badRequest().build());
    }

    return repo.findById(orderId)
      .flatMap(order -> {

        if (!canModify(order, authentication)) {
          return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).<TacoOrder>build());
        }

        if (patch.getDeliveryName() != null) {
          order.setDeliveryName(patch.getDeliveryName());
        }

        if (patch.getDeliveryStreet() != null) {
          order.setDeliveryStreet(patch.getDeliveryStreet());
        }

        if (patch.getDeliveryCity() != null) {
          order.setDeliveryCity(patch.getDeliveryCity());
        }

        if (patch.getDeliveryState() != null) {
          order.setDeliveryState(patch.getDeliveryState());
        }

        if (patch.getDeliveryZip() != null) {
          order.setDeliveryZip(patch.getDeliveryZip());
        }

        return repo.save(order)
            .map(ResponseEntity::ok);
      })
      .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  @DeleteMapping("/{orderId}")
  public Mono<ResponseEntity<Void>> deleteOrder(@PathVariable("orderId") String orderId,
    Authentication authentication) {

  return repo.findById(orderId)
      .flatMap(order -> {

        if (!canModify(order, authentication)) {
          return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).<Void>build());
        }

        if (order.getStatus() == TacoOrder.Status.PREPARING) {

          return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).<Void>build());
        }

        return repo.deleteById(orderId)
          .thenReturn(ResponseEntity.noContent().<Void>build());
      })
      .defaultIfEmpty(ResponseEntity.notFound().<Void>build());
}

  private boolean canModify(TacoOrder order,Authentication authentication) {

    if (authentication == null) {
      return false;
    }

    boolean isAdmin = authentication.getAuthorities()
                                    .stream()
                                    .anyMatch(authority ->
                                        "ROLE_ADMIN".equals(
                                            authority.getAuthority()));

    if (isAdmin) {
      return true;
    }

    return order.getUser() != null
      && order.getUser().getUsername() != null
      && order.getUser()
        .getUsername()
        .equals(authentication.getName());
  }
}