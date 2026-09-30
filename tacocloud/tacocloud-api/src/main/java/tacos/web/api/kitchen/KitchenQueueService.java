package tacos.web.api.kitchen;

import java.time.Clock;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.TacoOrder.ChangeOrigin;
import tacos.TacoOrder.OrderItem;
import tacos.TacoOrder.OrderStatusHistoryEntry;
import tacos.TacoOrder.Status;
import tacos.web.api.OrderWorkflowService;
import tacos.web.api.dto.ApiDtos.KitchenOrderItemResponse;
import tacos.web.api.dto.ApiDtos.KitchenOrderResponse;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

@Service
public class KitchenQueueService {

  private static final String KITCHEN = "ROLE_KITCHEN";

  private final ReactiveMongoTemplate mongo;
  private final OrderWorkflowService workflow;
  private final KitchenProperties properties;
  private final Clock clock;

  public KitchenQueueService(ReactiveMongoTemplate mongo,
      OrderWorkflowService workflow,KitchenProperties properties,Clock clock) {
    this.mongo = mongo;
    this.workflow = workflow;
    this.properties = properties;
    this.clock = clock;
  }

  public Flux<KitchenOrderResponse> queue(Authentication authentication) {
    requireKitchen(authentication);
    Query created = createdQueueQuery();
    return mongo.find(created,TacoOrder.class)
        .index()
        .map(indexed -> toResponse(indexed.getT2(),indexed.getT1()));
  }

  public Mono<KitchenOrderResponse> claimNext(Authentication authentication) {
    requireKitchen(authentication);
    workflow.validateTransition(Status.CREATED,Status.ACCEPTED,authentication);

    String stationId = properties.getStationId().trim();
    String cookId = authentication.getName();
    Query activeForStation = Query.query(Criteria.where("activeKitchenStationKey")
        .is(stationId).and("status").in(Status.ACCEPTED,Status.PREPARING));

    return mongo.exists(activeForStation,TacoOrder.class)
        .flatMap(busy -> busy
            ? Mono.error(stationBusy(stationId))
            : atomicClaim(stationId,cookId))
        .onErrorMap(DuplicateKeyException.class,
            error -> stationBusy(stationId));
  }

  public int estimatedPrepMinutes(TacoOrder order,long queueAhead) {
    KitchenProperties.Eta eta = properties.getEta();
    long quantity = totalQuantity(order);
    long complexity = complexityScore(order);
    long estimate = eta.getBaseMinutes()
        + Math.max(0L,queueAhead) * eta.getMinutesPerQueuedOrder()
        + quantity * eta.getMinutesPerItem()
        + complexity * eta.getMinutesPerComplexityPoint();
    return estimate > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimate;
  }

  private Mono<KitchenOrderResponse> atomicClaim(
      String stationId,String cookId) {
    OrderStatusHistoryEntry audit = new OrderStatusHistoryEntry(
        Status.CREATED,Status.ACCEPTED,Date.from(clock.instant()),cookId,
        ChangeOrigin.KITCHEN_API,"Claimed by kitchen");
    Update claim = new Update()
        .set("status",Status.ACCEPTED)
        .set("stationId",stationId)
        .set("cookId",cookId)
        .set("activeKitchenStationKey",stationId)
        .push("statusHistory",audit)
        .inc("version",1L);

    return mongo.findAndModify(
            createdQueueQuery(),claim,
            FindAndModifyOptions.options().returnNew(true),TacoOrder.class)
        .map(order -> toResponse(order,0));
  }

  private Query createdQueueQuery() {
    return Query.query(Criteria.where("status").is(Status.CREATED))
        .with(Sort.by(
            Sort.Order.asc("placedAt"),Sort.Order.asc("_id")));
  }

  private KitchenOrderResponse toResponse(TacoOrder order,long queueAhead) {
    List<KitchenOrderItemResponse> items = safeItems(order).stream()
        .map(item -> {
          Taco taco = item.getTaco();
          List<String> ingredientIds = taco != null
              && taco.getIngredients() != null
              ? taco.getIngredients().stream()
                  .filter(ingredient -> ingredient != null)
                  .map(Ingredient::getId)
                  .filter(id -> id != null)
                  .collect(Collectors.toList())
              : Collections.emptyList();
          return new KitchenOrderItemResponse(
              taco != null ? taco.getName() : null,ingredientIds,
              item.getQuantity());
        })
        .collect(Collectors.toList());
    return new KitchenOrderResponse(
        order.getId(),order.getPlacedAt(),
        order.getStatus() != null ? order.getStatus().name() : null,
        items,order.getStationId(),order.getCookId(),
        estimatedPrepMinutes(order,queueAhead));
  }

  private long totalQuantity(TacoOrder order) {
    List<OrderItem> items = safeItems(order);
    if (!items.isEmpty()) {
      return items.stream().mapToLong(item -> Math.max(0,item.getQuantity())).sum();
    }
    return order.getTacos() != null ? order.getTacos().size() : 0;
  }

  private long complexityScore(TacoOrder order) {
    List<OrderItem> items = safeItems(order);
    if (!items.isEmpty()) {
      return items.stream().mapToLong(item -> {
        Taco taco = item.getTaco();
        int ingredients = taco != null && taco.getIngredients() != null
            ? taco.getIngredients().size() : 0;
        return (long) Math.max(0,item.getQuantity()) * ingredients;
      }).sum();
    }
    return order.getTacos() == null ? 0 : order.getTacos().stream()
        .mapToLong(taco -> taco != null && taco.getIngredients() != null
            ? taco.getIngredients().size() : 0)
        .sum();
  }

  private List<OrderItem> safeItems(TacoOrder order) {
    return order != null && order.getItems() != null
        ? order.getItems() : Collections.emptyList();
  }

  private void requireKitchen(Authentication authentication) {
    boolean kitchen = authentication != null
        && authentication.isAuthenticated()
        && authentication.getAuthorities().stream()
            .anyMatch(authority -> KITCHEN.equals(authority.getAuthority()));
    if (!kitchen) {
      throw ApiException.forbidden(
          "KITCHEN_ACCESS_DENIED","Kitchen role is required.");
    }
  }

  private ApiException stationBusy(String stationId) {
    return ApiException.conflict(
        "KITCHEN_STATION_BUSY",
        "Kitchen station " + stationId + " already has an active order.");
  }
}
