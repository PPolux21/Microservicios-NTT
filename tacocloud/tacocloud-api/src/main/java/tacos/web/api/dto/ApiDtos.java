package tacos.web.api.dto;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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

    private String id;
    private String name;
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

    private String deliveryName;
    private String deliveryStreet;
    private String deliveryCity;
    private String deliveryState;
    private String deliveryZip;
    /*
    private String ccNumber;
    private String ccExpiration;
    private String ccCVV;
*/
    private List<OrderTacoRequest> tacos =
        new ArrayList<>();
  }


  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class OrderTacoRequest {

    private String name;

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