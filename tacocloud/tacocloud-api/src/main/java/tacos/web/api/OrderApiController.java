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
import tacos.web.api.dto.ApiDtos.OrderCreateRequest;
import tacos.web.api.dto.ApiDtos.OrderResponse;
import tacos.web.api.mapper.ApiMapper;
import tacos.web.api.mapper.ApiMapper.OrderCreateCommand;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;

@RestController
@RequestMapping(path="/api/orders",
                produces="application/json")
@CrossOrigin(origins="http://localhost:8080")
public class OrderApiController {

  private OrderRepository repo;

  /*
   * Se conserva para no modificar
   * innecesariamente el constructor
   * utilizado por pruebas anteriores.
   */
  private OrderMessagingService orderMessages;
  private OrderService orderService;

  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            OrderService orderService) {
    this.repo = repo;
    this.orderMessages = orderMessages;
    this.orderService = orderService;
  }

  @GetMapping(produces="application/json")
  public Flux<OrderResponse> allOrders() {
    return repo.findAll().map(ApiMapper::toResponse);
  }


  /*
   * TC-08
   * El controlador ya no recibe TacoOrder.
   */
  @PostMapping(consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrder(@RequestBody OrderCreateRequest request) {

    OrderCreateCommand command = ApiMapper.toCommand(request);

    return orderService.createOrder(command).map(ApiMapper::toResponse);
  }

  @PostMapping(path="fromEmail", consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrderFromEmail(@RequestBody Mono<EmailOrder> emailOrder) {
    return orderService.createOrderFromEmail(emailOrder).map(ApiMapper::toResponse);
  }


  /*
   * Se continúa utilizando
   * OrderDeliveryRequest creado
   * en TC-04/TC-05.
   */
  @PutMapping(path="/{orderId}", consumes="application/json")
  public Mono<ResponseEntity<OrderResponse>> putOrder(@PathVariable("orderId") String orderId,
          @RequestBody OrderDeliveryRequest request, Authentication authentication) {

    if (request.hasUnsupportedFields()) {
      return Mono.just(ResponseEntity.badRequest().build());
    }

    return repo.findById(orderId)
      .flatMap(existingOrder -> {

        if (!canModify(existingOrder, authentication)) {
          return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).<OrderResponse>build());
        }

        existingOrder.setDeliveryName(request.getDeliveryName());

        existingOrder.setDeliveryStreet(request.getDeliveryStreet());

        existingOrder.setDeliveryCity(request.getDeliveryCity());

        existingOrder.setDeliveryState(request.getDeliveryState());

        existingOrder.setDeliveryZip(request.getDeliveryZip());

        return repo.save(existingOrder)
          .map(savedOrder ->
              ResponseEntity.ok(ApiMapper.toResponse(savedOrder)));
      })
      .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  @PatchMapping(path = "/{orderId}",consumes = "application/json")
  public Mono<ResponseEntity<OrderResponse>> patchOrder(@PathVariable("orderId") String orderId,
          @RequestBody OrderDeliveryRequest patch,Authentication authentication) {

    if (patch.hasUnsupportedFields()) {
      return Mono.just(ResponseEntity.badRequest().build());
    }

    return repo.findById(orderId)
      .flatMap(order -> {

        if (!canModify(order, authentication)) {
          return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).<OrderResponse>build());
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
          .map(savedOrder ->
            ResponseEntity.ok(ApiMapper.toResponse(savedOrder)));
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