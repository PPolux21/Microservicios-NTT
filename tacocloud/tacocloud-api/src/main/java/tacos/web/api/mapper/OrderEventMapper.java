package tacos.web.api.mapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.TacoOrder.OrderItem;
import tacos.TacoOrder.Status;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderEventPayload.IngredientSnapshot;
import tacos.messaging.OrderEventPayload.OrderItemSnapshot;
import tacos.messaging.OrderEventType;

public final class OrderEventMapper {

  private OrderEventMapper() {
  }

  public static OrderEvent orderCreated(TacoOrder order,String correlationId) {
    return OrderEvent.create(OrderEventType.ORDER_CREATED,correlationId,
        payload(order,null,null,true));
  }

  public static OrderEvent statusChanged(TacoOrder order,Status previousStatus,
      String correlationId,String reason) {
    OrderEventType type = order.getStatus() == Status.CANCELLED
        ? OrderEventType.CANCELLED : OrderEventType.STATUS_CHANGED;
    return OrderEvent.create(type,correlationId,
        payload(order,previousStatus,
            type == OrderEventType.CANCELLED ? reason : null,false));
  }

  private static OrderEventPayload payload(TacoOrder order,
      Status previousStatus,String cancellationReason,boolean includeItems) {
    if (order == null) {
      throw new IllegalArgumentException("order is required");
    }
    Status status = order.getStatus() != null
        ? order.getStatus() : Status.CREATED;
    Instant placedAt = order.getPlacedAt() != null
        ? order.getPlacedAt().toInstant() : null;
    return new OrderEventPayload(
        order.getId(),status.name(),
        previousStatus != null ? previousStatus.name() : null,
        placedAt,includeItems ? items(order) : Collections.emptyList(),
        order.getStationId(),order.getCookId(),
        cancellationReason);
  }

  private static List<OrderItemSnapshot> items(TacoOrder order) {
    List<OrderItem> orderItems = order.getItems() != null
        ? order.getItems() : Collections.emptyList();
    if (!orderItems.isEmpty()) {
      return orderItems.stream()
          .map(item -> item(item.getTaco(),item.getQuantity()))
          .collect(Collectors.toList());
    }

    List<Taco> tacos = order.getTacos() != null
        ? order.getTacos() : Collections.emptyList();
    List<OrderItemSnapshot> snapshots = new ArrayList<>();
    for (Taco taco : tacos) {
      snapshots.add(item(taco,1));
    }
    return snapshots;
  }

  private static OrderItemSnapshot item(Taco taco,int quantity) {
    if (taco == null) {
      throw new IllegalArgumentException("order item taco is required");
    }
    List<IngredientSnapshot> ingredients = taco.getIngredients() != null
        ? taco.getIngredients().stream()
            .map(OrderEventMapper::ingredient)
            .collect(Collectors.toList())
        : Collections.emptyList();
    return new OrderItemSnapshot(taco.getName(),quantity,ingredients);
  }

  private static IngredientSnapshot ingredient(Ingredient ingredient) {
    if (ingredient == null) {
      throw new IllegalArgumentException("ingredient is required");
    }
    return new IngredientSnapshot(ingredient.getId(),ingredient.getName());
  }
}
