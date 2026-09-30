package tacos.web.api.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.Max;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;
import lombok.NoArgsConstructor;
import tacos.Ingredient.Type;
import tacos.Ingredient.Allergen;
import tacos.Ingredient.DietaryTag;
import tacos.Ingredient.SpiceLevel;
import tacos.TacoOrder.ChangeOrigin;
import tacos.TacoOrder.Status;

public final class ApiDtos {

  private ApiDtos() {}

  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class IngredientRequest {

    @NotBlank(message="id is required")
    @Pattern(regexp="[A-Z0-9_-]{2,20}",message="id has an invalid format")
    private String id;

    @NotBlank(message="name is required")
    @Size(max=50,message="name must have at most 50 characters")
    private String name;

    @NotNull(message="type is required")
    private Type type;

    @NotNull(message="dietaryTags is required")
    private Set<DietaryTag> dietaryTags =
        EnumSet.noneOf(DietaryTag.class);

    @NotNull(message="allergens is required")
    private Set<Allergen> allergens =
        EnumSet.noneOf(Allergen.class);

    @NotNull(message="spiceLevel is required")
    private SpiceLevel spiceLevel = SpiceLevel.NONE;
  }


  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class IngredientResponse {

    private String id;
    private String name;
    private Type type;
    private BigDecimal unitPrice;
    private boolean available;
    private Set<DietaryTag> dietaryTags;
    private Set<Allergen> allergens;
    private SpiceLevel spiceLevel;
  }

  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class IngredientCatalogUpdateRequest {

    @DecimalMin(value="0.00",message="unitPrice must not be negative")
    private BigDecimal unitPrice;

    private Boolean available;

    @Min(value=0,message="reorderLevel must not be negative")
    private Integer reorderLevel;

    @NotNull(message="expectedVersion is required")
    @Min(value=0,message="expectedVersion must not be negative")
    private Long expectedVersion;

    public boolean hasChanges() {
      return unitPrice != null || available != null || reorderLevel != null;
    }
  }

  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class StockAdjustmentRequest {

    @NotNull(message="quantity is required")
    private Integer quantity;

    @NotNull(message="expectedVersion is required")
    @Min(value=0,message="expectedVersion must not be negative")
    private Long expectedVersion;
  }

  @Data
  @AllArgsConstructor
  public static class IngredientAdminResponse {

    private String id;
    private String name;
    private Type type;
    private BigDecimal unitPrice;
    private boolean available;
    private int stockOnHand;
    private int reorderLevel;
    private Long version;
    private Set<DietaryTag> dietaryTags;
    private Set<Allergen> allergens;
    private SpiceLevel spiceLevel;
  }

  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class OrderCreateRequest {

    @NotBlank(message="deliveryName is required")
    @Size(max=50,message="deliveryName must have at most 50 characters")
    private String deliveryName;

    @NotBlank(message="deliveryStreet is required")
    @Size(max=100,message="deliveryStreet must have at most 100 characters")
    private String deliveryStreet;

    @NotBlank(message="deliveryCity is required")
    @Size(max=50,message="deliveryCity must have at most 50 characters")
    private String deliveryCity;

    @NotBlank(message="deliveryState is required")
    @Size(min=2,max=50,message="deliveryState must have between 2 and 50 characters")
    private String deliveryState;

    @NotBlank(message="deliveryZip is required")
    @Pattern(regexp="[A-Za-z0-9 -]{3,10}",message="deliveryZip has an invalid format")
    private String deliveryZip;

    @NotBlank
    private String paymentMethodId;

    @Size(max=50,message="couponCode must have at most 50 characters")
    private String couponCode;

    @Valid
    @NotNull(message="items are required")
    @Size(min=1,max=20,message="order must contain between 1 and 20 items")
    private List<OrderItemRequest> items =
        new ArrayList<>();
  }


  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class OrderQuoteRequest {

    @Valid
    @NotNull(message="items are required")
    @Size(min=1,max=20,message="quote must contain between 1 and 20 items")
    private List<OrderItemRequest> items = new ArrayList<>();

    @Size(max=50,message="couponCode must have at most 50 characters")
    private String couponCode;
  }


  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class OrderItemRequest {

    @Valid
    @NotNull(message="taco is required")
    private OrderTacoRequest taco;

    @NotNull(message="quantity is required")
    @Min(value=1,message="quantity must be at least 1")
    private Integer quantity;
  }


  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class OrderTacoRequest {

    @NotBlank(message="taco name is required")
    @Size(max=50,message="taco name must have at most 50 characters")
    private String name;

    @NotNull(message="ingredientIds are required")
    @Size(min=1,max=20,message="taco must contain between 1 and 20 ingredients")
    private List<@NotBlank(message="ingredient id is required") String> ingredientIds =
        new ArrayList<>();
  }

  @Data
  @AllArgsConstructor
  public static class OrderResponse {

    private String id;
    private Long version;

    private String deliveryName;
    private String deliveryStreet;
    private String deliveryCity;
    private String deliveryState;
    private String deliveryZip;

    private Date placedAt;
    private String status;
    private List<OrderStatusHistoryResponse> statusHistory;

    private List<OrderTacoResponse> tacos;
    private List<OrderItemResponse> items;
    private BigDecimal subtotal;
    private String appliedCouponCode;
    private BigDecimal discountAmount;
    private BigDecimal total;
    private String currency;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class OrderStatusHistoryResponse {
    private String fromStatus;
    private String toStatus;
    private Date changedAt;
    private String changedBy;
    private ChangeOrigin origin;
    private String reason;
  }

  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class OrderStatusChangeRequest {
    @NotNull(message="status is required")
    private Status status;

    @Size(max=200,message="reason must have at most 200 characters")
    private String reason;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class OrderSummaryResponse {
    private String id;
    private Date placedAt;
    private String status;
    private int itemCount;
    private BigDecimal total;
    private String currency;
  }

  @Data
  @NoArgsConstructor
  public static class OrderHistoryQuery {
    @Min(value=0,message="page must not be negative")
    private int page = 0;

    @Min(value=1,message="size must be at least 1")
    private int size = 20;
  }

  @Data
  @NoArgsConstructor
  public static class AdminOrderHistoryQuery extends OrderHistoryQuery {
    private String userId;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class OrderHistoryPageResponse {
    private List<OrderSummaryResponse> items;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
  }

  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ReorderRequest {
    @NotBlank(message="paymentMethodId is required")
    private String paymentMethodId;
    private boolean confirmPriceChange;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ReorderResponse {
    private String status;
    private boolean requiresConfirmation;
    private BigDecimal originalTotal;
    private BigDecimal currentTotal;
    private BigDecimal difference;
    private List<String> differences;
    private OrderResponse order;
  }


  @Data
  @AllArgsConstructor
  public static class OrderQuoteResponse {

    private boolean valid;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal total;
    private String currency;
    private List<TacoClassificationResponse> classifications;
  }


  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class TacoClassificationResponse {

    private String tacoId;
    private String tacoName;
    private Set<DietaryTag> dietaryTags;
    private Set<Allergen> allergens;
    private SpiceLevel spiceLevel;
    private String disclaimer;
  }


  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class TacoCatalogResponse {

    private String id;
    private String name;
    private Date createdAt;
    private List<IngredientResponse> ingredients;
    private TacoClassificationResponse classification;
  }


  @Data
  @NoArgsConstructor
  public static class TacoSearchQuery {

    @Size(max=50,message="name must have at most 50 characters")
    private String name;

    @Size(max=20,message="ingredientId must have at most 20 characters")
    private String ingredientId;

    private String diet;
    private String excludeAllergen;
    private String spice;

    @Min(value=0,message="page must not be negative")
    private int page = 0;

    @Min(value=1,message="size must be at least 1")
    private int size = 20;

    private String sort = "createdAt,desc";
  }


  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class TacoSearchResponse {
    private List<TacoCatalogResponse> items;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
  }


  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class DailyTacoResponse {
    private TacoCatalogResponse taco;
    private String date;
    private String reason;
  }


  @Data
  @NoArgsConstructor
  public static class FavoriteQuery {

    @Min(value=0,message="page must not be negative")
    private int page = 0;

    @Min(value=1,message="size must be at least 1")
    private int size = 20;
  }


  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class FavoritePageResponse {
    private List<TacoCatalogResponse> items;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
  }


  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class RatingRequest {

    @Min(value=1,message="score must be at least 1")
    @Max(value=5,message="score must not exceed 5")
    private int score;
  }


  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class TopTacoRatingResponse {
    private TacoCatalogResponse taco;
    private BigDecimal average;
    private long count;
    private Map<Integer,Long> distribution;
  }


  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class TacoDesignRequest {

    @NotBlank(message="taco name is required")
    @Size(max=50,message="taco name must have at most 50 characters")
    private String name;

    @NotNull(message="ingredientIds are required")
    @Size(max=50,message="ingredientIds must have at most 50 entries")
    private List<@NotBlank(message="ingredient id is required") String>
        ingredientIds = new ArrayList<>();
  }


  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class TacoDesignValidationResponse {
    private boolean valid;
    private List<TacoDesignViolationResponse> violations;
  }


  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class TacoDesignViolationResponse {
    private String code;
    private String message;
  }


  @Data
  @AllArgsConstructor
  public static class OrderItemResponse {

    private OrderTacoResponse taco;
    private int quantity;
    private BigDecimal unitPriceAtPurchase;
    private BigDecimal subtotal;
  }


  @Data
  @AllArgsConstructor
  public static class OrderTacoResponse {

    private String name;
    private List<IngredientResponse> ingredients;
  }

  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class PaymentTokenizeRequest {

    @NotBlank
    @Pattern(regexp = "\\d{12,19}")
    @JsonProperty(
        access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
    private String cardNumber;


    @NotBlank
    @Pattern(regexp = "\\d{3,4}")
    @JsonProperty(
        access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
    private String cvv;


    @NotBlank
    @Pattern(regexp = "\\d{2}/\\d{2}")
    private String expiration;
  }

  @Data
  @AllArgsConstructor
  public static class PaymentMethodResponse {

    private String id;

    private String brand;

    private String last4;
  }
}
