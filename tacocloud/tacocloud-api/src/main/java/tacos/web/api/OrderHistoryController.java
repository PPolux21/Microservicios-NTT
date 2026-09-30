package tacos.web.api;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.web.api.dto.ApiDtos.AdminOrderHistoryQuery;
import tacos.web.api.dto.ApiDtos.OrderHistoryPageResponse;
import tacos.web.api.dto.ApiDtos.OrderHistoryQuery;
import tacos.web.api.dto.ApiDtos.OrderResponse;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.mapper.ApiMapper;

@RestController
public class OrderHistoryController {

  private final OrderService orderService;
  private int maxPageSize = 50;

  public OrderHistoryController(OrderService orderService) {
    this.orderService = orderService;
  }

  @Value("${tacocloud.search.max-page-size:50}")
  void configureMaxPageSize(int maxPageSize) {
    this.maxPageSize = maxPageSize;
  }

  @GetMapping(
      path={"/api/users/me/orders","/api/v1/users/me/orders"},
      produces="application/json")
  public Mono<OrderHistoryPageResponse> ownOrders(
      @Valid OrderHistoryQuery query,Authentication authentication) {
    validatePageSize(query.getSize());
    return orderService.findOwnOrderHistory(
        authentication,query.getPage(),query.getSize())
        .map(ApiMapper::toResponse);
  }

  @GetMapping(
      path={"/api/users/me/orders/{orderId}",
          "/api/v1/users/me/orders/{orderId}"},
      produces="application/json")
  public Mono<OrderResponse> ownOrder(@PathVariable String orderId,
      Authentication authentication) {
    return orderService.findOwnOrder(orderId,authentication)
        .map(ApiMapper::toResponse);
  }

  @GetMapping(
      path={"/api/admin/orders","/api/v1/admin/orders"},
      produces="application/json")
  public Mono<OrderHistoryPageResponse> adminOrders(
      @Valid AdminOrderHistoryQuery query) {
    validatePageSize(query.getSize());
    return orderService.findAdminOrderHistory(
        query.getUserId(),query.getPage(),query.getSize())
        .map(ApiMapper::toResponse);
  }

  private void validatePageSize(int size) {
    if (size > maxPageSize) {
      throw ApiException.unprocessable(
          "PAGE_SIZE_EXCEEDED",
          "Page size must not exceed " + maxPageSize + ".");
    }
  }
}
