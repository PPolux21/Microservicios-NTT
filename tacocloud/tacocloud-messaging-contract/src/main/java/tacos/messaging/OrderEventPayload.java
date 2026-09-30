package tacos.messaging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown=true)
public final class OrderEventPayload {

  private final String orderId;
  private final String status;
  private final String previousStatus;
  private final Instant placedAt;
  private final List<OrderItemSnapshot> items;
  private final String stationId;
  private final String cookId;
  private final String cancellationReason;

  @JsonCreator
  public OrderEventPayload(
      @JsonProperty(value="orderId",required=true) String orderId,
      @JsonProperty(value="status",required=true) String status,
      @JsonProperty("previousStatus") String previousStatus,
      @JsonProperty("placedAt") Instant placedAt,
      @JsonProperty("items") List<OrderItemSnapshot> items,
      @JsonProperty("stationId") String stationId,
      @JsonProperty("cookId") String cookId,
      @JsonProperty("cancellationReason") String cancellationReason) {
    this.orderId = requireText(orderId,"orderId");
    this.status = requireText(status,"status");
    this.previousStatus = previousStatus;
    this.placedAt = placedAt;
    this.items = Collections.unmodifiableList(
        new ArrayList<>(items != null ? items : Collections.emptyList()));
    this.stationId = stationId;
    this.cookId = cookId;
    this.cancellationReason = cancellationReason;
  }

  private static String requireText(String value,String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  public String getOrderId() { return orderId; }
  public String getStatus() { return status; }
  public String getPreviousStatus() { return previousStatus; }
  public Instant getPlacedAt() { return placedAt; }
  public List<OrderItemSnapshot> getItems() { return items; }
  public String getStationId() { return stationId; }
  public String getCookId() { return cookId; }
  public String getCancellationReason() { return cancellationReason; }

  @JsonIgnoreProperties(ignoreUnknown=true)
  public static final class OrderItemSnapshot {
    private final String tacoName;
    private final int quantity;
    private final List<IngredientSnapshot> ingredients;

    @JsonCreator
    public OrderItemSnapshot(
        @JsonProperty(value="tacoName",required=true) String tacoName,
        @JsonProperty(value="quantity",required=true) int quantity,
        @JsonProperty("ingredients") List<IngredientSnapshot> ingredients) {
      this.tacoName = requireText(tacoName,"tacoName");
      if (quantity < 1) {
        throw new IllegalArgumentException("quantity must be positive");
      }
      this.quantity = quantity;
      this.ingredients = Collections.unmodifiableList(new ArrayList<>(
          ingredients != null ? ingredients : Collections.emptyList()));
    }

    public String getTacoName() { return tacoName; }
    public int getQuantity() { return quantity; }
    public List<IngredientSnapshot> getIngredients() { return ingredients; }
  }

  @JsonIgnoreProperties(ignoreUnknown=true)
  public static final class IngredientSnapshot {
    private final String ingredientId;
    private final String ingredientName;

    @JsonCreator
    public IngredientSnapshot(
        @JsonProperty(value="ingredientId",required=true) String ingredientId,
        @JsonProperty(value="ingredientName",required=true) String ingredientName) {
      this.ingredientId = requireText(ingredientId,"ingredientId");
      this.ingredientName = requireText(ingredientName,"ingredientName");
    }

    public String getIngredientId() { return ingredientId; }
    public String getIngredientName() { return ingredientName; }

    @Override
    public boolean equals(Object other) {
      if (this == other) { return true; }
      if (!(other instanceof IngredientSnapshot)) { return false; }
      IngredientSnapshot that = (IngredientSnapshot) other;
      return ingredientId.equals(that.ingredientId)
          && ingredientName.equals(that.ingredientName);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ingredientId,ingredientName);
    }
  }
}
