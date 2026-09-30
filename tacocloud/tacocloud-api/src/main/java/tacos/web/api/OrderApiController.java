package tacos.web.api;

import org.springframework.security.core.Authentication;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.web.api.dto.ApiDtos.OrderCreateRequest;
import tacos.web.api.dto.ApiDtos.OrderResponse;
import tacos.web.api.dto.ApiDtos.OrderQuoteRequest;
import tacos.web.api.dto.ApiDtos.OrderQuoteResponse;
import tacos.web.api.dto.ApiDtos.ReorderRequest;
import tacos.web.api.dto.ApiDtos.ReorderResponse;
import tacos.web.api.dto.ApiDtos.OrderStatusChangeRequest;
import tacos.web.api.dto.ApiDtos.OrderStatusResponse;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.mapper.ApiMapper;
import tacos.web.api.mapper.ApiMapper.OrderCreateCommand;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;

@RestController
@RequestMapping(path="/api/orders",
                produces="application/json")
public class OrderApiController {

  private OrderRepository repo;

  /*
   * Se conserva para no modificar
   * innecesariamente el constructor
   * utilizado por pruebas anteriores.
   */
  private OrderMessagingService orderMessages;
  private OrderService orderService;
  private OrderWorkflowService workflowService;

  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            OrderService orderService,
                            OrderWorkflowService workflowService) {
    this.repo = repo;
    this.orderMessages = orderMessages;
    this.orderService = orderService;
    this.workflowService = workflowService;
  }

  @PostMapping(consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrder(
      @Valid @RequestBody OrderCreateRequest request,
      Authentication authentication) {

    OrderCreateCommand command = ApiMapper.toCommand(request);

    return orderService
      .createOrder(command,authentication)
      .map(ApiMapper::toResponse);
  }

  @PostMapping(path="/quote",consumes="application/json")
  public Mono<OrderQuoteResponse> quote(
      @Valid @RequestBody OrderQuoteRequest request) {
    return orderService.quote(ApiMapper.toCommand(request))
        .map(ApiMapper::toResponse);
  }

  @PostMapping(path="/{orderId}/reorder",consumes="application/json")
  public Mono<ReorderResponse> reorder(
      @PathVariable String orderId,
      @RequestHeader(name="Idempotency-Key",required=false) String idempotencyKey,
      @Valid @RequestBody ReorderRequest request,
      Authentication authentication) {
    return orderService.reorder(
        orderId,request.getPaymentMethodId(),request.isConfirmPriceChange(),
        idempotencyKey,authentication)
        .map(ApiMapper::toResponse);
  }

  @PatchMapping(path="/{orderId}/status",consumes="application/json")
  public Mono<OrderStatusResponse> changeStatus(
      @PathVariable String orderId,
      @Valid @RequestBody OrderStatusChangeRequest request,
      Authentication authentication) {
    return workflowService.transition(
        orderId,request.getStatus(),request.getReason(),authentication)
        .map(ApiMapper::toStatusResponse);
  }

  @PostMapping(path="/{orderId}/cancel")
  public Mono<OrderResponse> cancelOrder(
      @PathVariable String orderId,Authentication authentication) {
    return workflowService.cancel(
        orderId,"Cancelled by owner",authentication)
        .map(ApiMapper::toResponse);
  }

  @PostMapping(path="fromEmail", consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrderFromEmail(@RequestBody Mono<EmailOrder> emailOrder) {
    return orderService.createOrderFromEmail(emailOrder).map(ApiMapper::toResponse);
  }


  @PutMapping(path="/{orderId}", consumes="application/json")
  public Mono<ResponseEntity<OrderResponse>>
      putOrder(@PathVariable("orderId") String orderId,
          @Valid @RequestBody OrderDeliveryRequest request,
          Authentication authentication) {

    if (request.hasUnsupportedFields()) {
      return Mono.error(
        ApiException.badRequest("UNSUPPORTED_FIELDS","The request contains unsupported fields."));
    }

    return orderService.findAccessibleOrder(orderId,authentication)
      .flatMap(existingOrder -> {
        existingOrder.setDeliveryName(request.getDeliveryName());

        existingOrder.setDeliveryStreet(request.getDeliveryStreet());

        existingOrder.setDeliveryCity(request.getDeliveryCity());

        existingOrder.setDeliveryState(request.getDeliveryState());

        existingOrder.setDeliveryZip(request.getDeliveryZip());

        return repo.save(existingOrder)
          .map(savedOrder ->
              ResponseEntity.ok(ApiMapper.toResponse(savedOrder)));
      });
  }

  @PatchMapping(path="/{orderId}",consumes="application/json")
  public Mono<ResponseEntity<OrderResponse>>
      patchOrder(@PathVariable("orderId") String orderId,
        @Valid @RequestBody OrderDeliveryRequest patch,
        Authentication authentication) {

    if (patch.hasUnsupportedFields()) {
      return Mono.error(
        ApiException.badRequest("UNSUPPORTED_FIELDS","The request contains unsupported fields."));
    }

    return orderService.findAccessibleOrder(orderId,authentication)
      .flatMap(order -> {
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
      });
  }

  @DeleteMapping("/{orderId}")
  public Mono<ResponseEntity<Void>>deleteOrder(@PathVariable("orderId") String orderId,
        Authentication authentication) {
    return workflowService.cancel(
        orderId,"Cancelled through legacy DELETE endpoint",authentication)
        .thenReturn(ResponseEntity.noContent().<Void>build());
  }

}
