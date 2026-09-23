package tacos.web.api.mapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Data;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.TacoOrder;

import tacos.web.api.dto.ApiDtos.IngredientRequest;
import tacos.web.api.dto.ApiDtos.IngredientResponse;
import tacos.web.api.dto.ApiDtos.OrderCreateRequest;
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
        ingredient.getType());
  }


  /*
   * OrderCreateRequest -> OrderCreateCommand
   */
  public static OrderCreateCommand toCommand(OrderCreateRequest request) {

    List<TacoCommand> tacos =
      request.getTacos() != null
        ? request.getTacos()
            .stream()
            .map(taco -> {

              List<IngredientCommand> ingredients = 
                  taco.getIngredients() != null
                      ? taco.getIngredients()
                          .stream()
                          .map(ingredient ->
                              new IngredientCommand(
                                  ingredient.getId(),
                                  ingredient.getName(),
                                  ingredient.getType()))
                          .collect(Collectors.toList())
                      : Collections.emptyList();

              return new TacoCommand(taco.getName(),ingredients);
            }).collect(Collectors.toList())
        : Collections.emptyList();


    return new OrderCreateCommand(
        request.getDeliveryName(),
        request.getDeliveryStreet(),
        request.getDeliveryCity(),
        request.getDeliveryState(),
        request.getDeliveryZip(),
        /*
        request.getCcNumber(),
        request.getCcExpiration(),
        request.getCcCVV(),
        */
        tacos);
  }

  public static TacoOrder toEntity(OrderCreateCommand command) {

    TacoOrder order = new TacoOrder();

    order.setDeliveryName(command.getDeliveryName());
    order.setDeliveryStreet(command.getDeliveryStreet());
    order.setDeliveryCity(command.getDeliveryCity());
    order.setDeliveryState(command.getDeliveryState());
    order.setDeliveryZip(command.getDeliveryZip());
    /*
    order.setCcNumber(command.getCcNumber());
    order.setCcExpiration(command.getCcExpiration());
    order.setCcCVV(command.getCcCVV());
    */

    for (TacoCommand tacoCommand : command.getTacos()) {
      Taco taco = new Taco();

      taco.setName(tacoCommand.getName());

      List<Ingredient> ingredients = new ArrayList<>();

      for (IngredientCommand ingredientCommand : tacoCommand.getIngredients()) {
        ingredients.add(new Ingredient(
                ingredientCommand.getId(),
                ingredientCommand.getName(),
                ingredientCommand.getType()));
      }

      taco.setIngredients(ingredients);

      order.addTaco(taco);
    }

    return order;
  }


  public static OrderResponse toResponse(TacoOrder order) {

    List<Taco> domainTacos = order.getTacos() != null ? order.getTacos() : Collections.emptyList();

    List<OrderTacoResponse> tacos = domainTacos.stream()
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
        order.getStatus() != null ? order.getStatus().name() : null,tacos);
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
    /*
    private String ccNumber;
    private String ccExpiration;
    private String ccCVV;
    */
    private List<TacoCommand> tacos;
  }


  @Data
  @AllArgsConstructor
  public static class TacoCommand {

    private String name;

    private List<IngredientCommand> ingredients;
  }


  @Data
  @AllArgsConstructor
  public static class IngredientCommand {

    private String id;
    private String name;
    private Type type;
  }
}