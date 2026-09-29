package tacos.web.api.mapper;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Data;
import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.TacoOrder.OrderItem;

import tacos.web.api.dto.ApiDtos.IngredientRequest;
import tacos.web.api.dto.ApiDtos.DailyTacoResponse;
import tacos.web.api.dto.ApiDtos.FavoritePageResponse;
import tacos.web.api.dto.ApiDtos.IngredientAdminResponse;
import tacos.web.api.dto.ApiDtos.IngredientResponse;
import tacos.web.api.dto.ApiDtos.OrderCreateRequest;
import tacos.web.api.dto.ApiDtos.OrderQuoteRequest;
import tacos.web.api.dto.ApiDtos.OrderQuoteResponse;
import tacos.web.api.dto.ApiDtos.OrderItemResponse;
import tacos.web.api.dto.ApiDtos.OrderResponse;
import tacos.web.api.dto.ApiDtos.OrderSummaryResponse;
import tacos.web.api.dto.ApiDtos.OrderHistoryPageResponse;
import tacos.web.api.dto.ApiDtos.OrderTacoResponse;
import tacos.web.api.dto.ApiDtos.TacoCatalogResponse;
import tacos.web.api.dto.ApiDtos.TacoClassificationResponse;
import tacos.web.api.dto.ApiDtos.TacoDesignValidationResponse;
import tacos.web.api.dto.ApiDtos.TacoDesignViolationResponse;
import tacos.web.api.dto.ApiDtos.TacoSearchResponse;
import tacos.web.api.dto.ApiDtos.TopTacoRatingResponse;
import tacos.web.api.TacoClassificationService.ClassifiedTaco;
import tacos.web.api.TacoClassificationService.TacoClassification;
import tacos.web.api.TacoClassificationService;
import tacos.web.api.TacoDesignValidator.ValidationResult;
import tacos.web.api.DailyTacoService.DailyTacoRecommendation;
import tacos.web.api.FavoriteService.FavoritePage;
import tacos.web.api.OrderService.OrderHistoryPage;
import tacos.web.api.TacoRatingService.RankedTaco;
import tacos.data.TacoSearchRepository.TacoSearchPage;

public final class ApiMapper {

  private ApiMapper() {
  }

  public static Ingredient toEntity(IngredientRequest request) {

    Ingredient ingredient = new Ingredient(
        request.getId(),
        request.getName(),
        request.getType());
    ingredient.setDietaryTags(copyDietaryTags(request.getDietaryTags()));
    ingredient.setAllergens(copyAllergens(request.getAllergens()));
    ingredient.setSpiceLevel(request.getSpiceLevel());
    return ingredient;
  }

  public static IngredientResponse toResponse(Ingredient ingredient) {

    return new IngredientResponse(
        ingredient.getId(),
        ingredient.getName(),
        ingredient.getType(),
        ingredient.getUnitPrice(),
        ingredient.isAvailable(),
        copyDietaryTags(ingredient.getDietaryTags()),
        copyAllergens(ingredient.getAllergens()),
        safeSpiceLevel(ingredient.getSpiceLevel()));
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
        ingredient.getVersion(),
        copyDietaryTags(ingredient.getDietaryTags()),
        copyAllergens(ingredient.getAllergens()),
        safeSpiceLevel(ingredient.getSpiceLevel()));
  }


  /*
   * OrderCreateRequest -> OrderCreateCommand
   */
  public static OrderCreateCommand toCommand(OrderCreateRequest request) {

    return new OrderCreateCommand(
        request.getDeliveryName(),
        request.getDeliveryStreet(),
        request.getDeliveryCity(),
        request.getDeliveryState(),
        request.getDeliveryZip(),
        request.getPaymentMethodId(),
        toItemCommands(request.getItems()),
        request.getCouponCode());
  }

  public static OrderQuoteCommand toCommand(OrderQuoteRequest request) {
    return new OrderQuoteCommand(
        toItemCommands(request.getItems()),request.getCouponCode());
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
        order.getSubtotal(),
        order.getAppliedCouponCode(),
        order.getDiscountAmount(),
        order.getTotal(),
        order.getCurrency());
  }

  public static OrderSummaryResponse toSummaryResponse(TacoOrder order) {
    int itemCount = order.getItems() == null
        ? 0
        : order.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
    return new OrderSummaryResponse(
        order.getId(),order.getPlacedAt(),
        order.getStatus() != null ? order.getStatus().name() : null,
        itemCount,order.getTotal(),order.getCurrency());
  }

  public static OrderHistoryPageResponse toResponse(OrderHistoryPage page) {
    return new OrderHistoryPageResponse(
        page.getItems().stream()
            .map(ApiMapper::toSummaryResponse)
            .collect(Collectors.toList()),
        page.getPage(),page.getSize(),page.getTotalElements(),
        page.getTotalPages());
  }

  public static OrderQuoteResponse toResponse(OrderQuote quote) {
    return new OrderQuoteResponse(
        quote.isValid(),quote.getSubtotal(),quote.getDiscount(),
        quote.getTotal(),quote.getCurrency(),
        (quote.getClassifications() != null
            ? quote.getClassifications()
            : Collections.<ClassifiedTaco>emptyList()).stream()
            .map(ApiMapper::toClassificationResponse)
            .collect(Collectors.toList()));
  }

  public static TacoCatalogResponse toResponse(ClassifiedTaco classified) {
    Taco taco = classified.getTaco();
    List<IngredientResponse> ingredients = classified.getIngredients().stream()
        .map(ApiMapper::toResponse)
        .collect(Collectors.toList());
    return new TacoCatalogResponse(
        taco.getId(),taco.getName(),taco.getCreatedAt(),ingredients,
        toClassificationResponse(classified));
  }

  public static TacoClassificationResponse toClassificationResponse(
      ClassifiedTaco classified) {
    Taco taco = classified.getTaco();
    TacoClassification classification = classified.getClassification();
    return new TacoClassificationResponse(
        taco.getId(),taco.getName(),
        copyDietaryTags(classification.getDietaryTags()),
        copyAllergens(classification.getAllergens()),
        classification.getSpiceLevel(),
        tacos.web.api.TacoClassificationService.DISCLAIMER);
  }

  public static TacoDesignValidationResponse toResponse(
      ValidationResult result) {
    List<TacoDesignViolationResponse> violations = result.getViolations()
        .stream()
        .map(violation -> new TacoDesignViolationResponse(
            violation.getCode(),violation.getMessage()))
        .collect(Collectors.toList());
    return new TacoDesignValidationResponse(result.isValid(),violations);
  }

  public static TacoSearchResponse toResponse(TacoSearchPage page,
      TacoClassificationService classificationService) {
    List<TacoCatalogResponse> items = page.getItems().stream()
        .map(taco -> {
          List<Ingredient> ingredients = taco.getIngredients() != null
              ? taco.getIngredients()
              : Collections.emptyList();
          return toResponse(new ClassifiedTaco(
              taco,ingredients,
              classificationService.classifyIngredients(ingredients)));
        })
        .collect(Collectors.toList());
    return new TacoSearchResponse(
        items,page.getPage(),page.getSize(),page.getTotalElements(),
        page.getTotalPages());
  }

  public static DailyTacoResponse toResponse(
      DailyTacoRecommendation recommendation) {
    return new DailyTacoResponse(
        toResponse(recommendation.getTaco()),
        recommendation.getDate().toString(),recommendation.getReason());
  }

  public static FavoritePageResponse toResponse(FavoritePage page) {
    List<TacoCatalogResponse> items = page.getItems().stream()
        .map(ApiMapper::toResponse)
        .collect(Collectors.toList());
    return new FavoritePageResponse(
        items,page.getPage(),page.getSize(),page.getTotalElements(),
        page.getTotalPages());
  }

  public static List<TopTacoRatingResponse> toRatingResponses(
      List<RankedTaco> ratings) {
    return ratings.stream()
        .map(rating -> new TopTacoRatingResponse(
            toResponse(rating.getTaco()),rating.getAverage(),
            rating.getCount(),rating.getDistribution()))
        .collect(Collectors.toList());
  }

  private static List<OrderItemCommand> toItemCommands(
      List<tacos.web.api.dto.ApiDtos.OrderItemRequest> requestItems) {
    return requestItems != null
        ? requestItems.stream().map(item -> {
          List<String> ingredientIds = item.getTaco().getIngredientIds() != null
              ? new ArrayList<>(item.getTaco().getIngredientIds())
              : Collections.emptyList();
          return new OrderItemCommand(
              new TacoCommand(item.getTaco().getName(),ingredientIds),
              item.getQuantity());
        }).collect(Collectors.toList())
        : Collections.emptyList();
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
    private String couponCode;
  }

  @Data
  @AllArgsConstructor
  public static class OrderQuoteCommand {
    private List<OrderItemCommand> items;
    private String couponCode;
  }

  @Data
  @AllArgsConstructor
  public static class OrderQuote {
    private boolean valid;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal total;
    private String currency;
    private List<ClassifiedTaco> classifications;
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

  private static Set<tacos.Ingredient.DietaryTag> copyDietaryTags(
      Set<tacos.Ingredient.DietaryTag> tags) {
    return tags == null || tags.isEmpty()
        ? EnumSet.noneOf(tacos.Ingredient.DietaryTag.class)
        : EnumSet.copyOf(tags);
  }

  private static Set<tacos.Ingredient.Allergen> copyAllergens(
      Set<tacos.Ingredient.Allergen> allergens) {
    return allergens == null || allergens.isEmpty()
        ? EnumSet.noneOf(tacos.Ingredient.Allergen.class)
        : EnumSet.copyOf(allergens);
  }

  private static tacos.Ingredient.SpiceLevel safeSpiceLevel(
      tacos.Ingredient.SpiceLevel spiceLevel) {
    return spiceLevel != null
        ? spiceLevel
        : tacos.Ingredient.SpiceLevel.NONE;
  }
}
