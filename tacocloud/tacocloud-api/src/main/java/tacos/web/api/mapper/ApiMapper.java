package tacos.web.api.mapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Data;
import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.TacoOrder.OrderItem;

import tacos.web.api.dto.ApiDtos.IngredientRequest;
import tacos.web.api.dto.ApiDtos.IngredientAdminResponse;
import tacos.web.api.dto.ApiDtos.IngredientResponse;
import tacos.web.api.dto.ApiDtos.OrderCreateRequest;
import tacos.web.api.dto.ApiDtos.OrderItemResponse;
import tacos.web.api.dto.ApiDtos.OrderResponse;
import tacos.web.api.dto.ApiDtos.OrderTacoResponse;

public final class ApiMapper {

  private ApiMapper() {
  }

  public static Ingredient toEntity(IngredientRequest request) {

    return new Ingredient(
        request.getId(),
        request.getName(),
        request.getType());
  }

  public static IngredientResponse toResponse(Ingredient ingredient) {

    return new IngredientResponse(
        ingredient.getId(),
        ingredient.getName(),
        ingredient.getType(),
        ingredient.getUnitPrice(),
        ingredient.isAvailable());
  }

  public static IngredientAdminResponse toAdminResponse(
      Ingredient ingredient) {

    return new IngredientAdminResponse(
        ingredient.getId(),
        ingredient.getName(),
        ingredient.getType(),
        ingredient.getUnitPrice(),
        ingredient.isAvailable(),
        ingredient.getStockOnHand(),
        ingredient.getReorderLevel(),
        ingredient.getVersion());
  }


  /*
   * OrderCreateRequest -> OrderCreateCommand
   */
  public static OrderCreateCommand toCommand(OrderCreateRequest request) {

    List<OrderItemCommand> items =
      request.getItems() != null
        ? request.getItems()
            .stream()
            .map(item -> {
              List<String> ingredientIds = item.getTaco().getIngredientIds() != null
                  ? new ArrayList<>(item.getTaco().getIngredientIds())
                  : Collections.emptyList();

              TacoCommand taco = new TacoCommand(
                  item.getTaco().getName(),ingredientIds);

              return new OrderItemCommand(taco,item.getQuantity());
            }).collect(Collectors.toList())
        : Collections.emptyList();


    return new OrderCreateCommand(
        request.getDeliveryName(),
        request.getDeliveryStreet(),
        request.getDeliveryCity(),
        request.getDeliveryState(),
        request.getDeliveryZip(),
        request.getPaymentMethodId(),
        items);
  }

  public static TacoOrder toEntity(OrderCreateCommand command) {

    TacoOrder order = new TacoOrder();

    order.setDeliveryName(command.getDeliveryName());
    order.setDeliveryStreet(command.getDeliveryStreet());
    order.setDeliveryCity(command.getDeliveryCity());
    order.setDeliveryState(command.getDeliveryState());
    order.setDeliveryZip(command.getDeliveryZip());

    return order;
  }


  public static OrderResponse toResponse(TacoOrder order) {

    List<Taco> domainTacos = order.getTacos() != null ? order.getTacos() : Collections.emptyList();

    List<OrderTacoResponse> tacos = domainTacos.stream()
        .map(ApiMapper::toResponse)
        .collect(Collectors.toList());

    List<OrderItem> domainItems = order.getItems() != null
        ? order.getItems()
        : Collections.emptyList();

    List<OrderItemResponse> items = domainItems.stream()
        .map(ApiMapper::toResponse)
        .collect(Collectors.toList());

    return new OrderResponse(
        order.getId(),
        order.getDeliveryName(),
        order.getDeliveryStreet(),
        order.getDeliveryCity(),
        order.getDeliveryState(),
        order.getDeliveryZip(),
        order.getPlacedAt(),
        order.getStatus() != null ? order.getStatus().name() : null,
        tacos,
        items,
        order.getTotal(),
        order.getCurrency());
  }


  private static OrderItemResponse toResponse(OrderItem item) {

    return new OrderItemResponse(
        toResponse(item.getTaco()),
        item.getQuantity(),
        item.getUnitPriceAtPurchase(),
        item.getSubtotal());
  }


  private static OrderTacoResponse toResponse(Taco taco) {

    List<Ingredient> domainIngredients = taco.getIngredients() != null ? taco.getIngredients() : Collections.emptyList();

    List<IngredientResponse> ingredients = domainIngredients.stream()
        .map(ApiMapper::toResponse)
        .collect(Collectors.toList());

    return new OrderTacoResponse(taco.getName(),ingredients);
  }


  @Data
  @AllArgsConstructor
  public static class OrderCreateCommand {

    private String deliveryName;
    private String deliveryStreet;
    private String deliveryCity;
    private String deliveryState;
    private String deliveryZip;
    private String paymentMethodId;
    private List<OrderItemCommand> items;
  }


  @Data
  @AllArgsConstructor
  public static class OrderItemCommand {

    private TacoCommand taco;
    private Integer quantity;
  }


  @Data
  @AllArgsConstructor
  public static class TacoCommand {

    private String name;
    private List<String> ingredientIds;
  }
}
