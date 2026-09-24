package tacos.web.api.dto;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
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
}