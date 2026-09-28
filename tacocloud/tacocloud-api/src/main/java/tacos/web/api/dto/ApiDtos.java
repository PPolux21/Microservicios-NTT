package tacos.web.api.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
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
  }


  @Data
  @AllArgsConstructor
  public static class IngredientResponse {

    private String id;
    private String name;
    private Type type;
    private BigDecimal unitPrice;
    private boolean available;
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

    @Valid
    @NotNull(message="tacos are required")
    @Size(min=1,max=20,message="order must contain between 1 and 20 tacos")
    private List<OrderTacoRequest> tacos =
        new ArrayList<>();
  }


  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class OrderTacoRequest {

    @NotBlank(message="taco name is required")
    @Size(max=50,message="taco name must have at most 50 characters")
    private String name;

    @Valid
    @NotNull(message="ingredients are required")
    @Size(min=1,max=20,message="taco must contain between 1 and 20 ingredients")
    private List<IngredientRequest> ingredients =
        new ArrayList<>();
  }

  @Data
  @AllArgsConstructor
  public static class OrderResponse {

    private String id;

    private String deliveryName;
    private String deliveryStreet;
    private String deliveryCity;
    private String deliveryState;
    private String deliveryZip;

    private Date placedAt;
    private String status;

    private List<OrderTacoResponse> tacos;
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
