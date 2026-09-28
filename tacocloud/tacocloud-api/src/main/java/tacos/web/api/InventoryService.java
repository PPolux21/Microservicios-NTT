package tacos.web.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.InventoryReservation;
import tacos.InventoryReservation.ReservationItem;
import tacos.InventoryReservation.Status;
import tacos.TacoOrder;
import tacos.TacoOrder.OrderItem;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

@Service
public class InventoryService {

  private final ReactiveMongoTemplate mongo;

  public InventoryService(ReactiveMongoTemplate mongo) {
    this.mongo = mongo;
  }

  public Mono<InventoryReservation> reserve(TacoOrder orderDraft) {

    return Mono.defer(() -> {
      if (orderDraft == null || orderDraft.getId() == null) {
        return Mono.error(ApiException.conflict(
            "RESERVATION_ID_REQUIRED","Order draft must have a reservation id."));
      }

      List<ReservationItem> items = aggregate(orderDraft);
      InventoryReservation reservation = new InventoryReservation(
          orderDraft.getId(),orderDraft.getId(),Status.PENDING,items);

      return mongo.insert(reservation)
          .flatMap(this::reserveInserted)
          .onErrorResume(DuplicateKeyException.class,
              error -> existingReservation(reservation.getId()));
    });
  }

  public Mono<Void> release(String reservationId) {

    if (reservationId == null) {
      return Mono.empty();
    }

    Query claim = Query.query(Criteria.where("_id").is(reservationId)
        .and("status").is(Status.RESERVED));
    Update released = Update.update("status",Status.RELEASED);

    return mongo.findAndModify(
            claim,released,FindAndModifyOptions.options().returnNew(true),
            InventoryReservation.class)
        .flatMap(reservation -> releaseItems(reservation.getItems()))
        .then();
  }

  private Mono<InventoryReservation> reserveInserted(
      InventoryReservation reservation) {

    return Mono.defer(() -> {
      List<ReservationItem> reserved = new ArrayList<>();

      return Flux.fromIterable(reservation.getItems())
          .concatMap(item -> decrement(item)
              .doOnNext(ignored -> reserved.add(item)))
          .then(markReserved(reservation.getId()))
          .onErrorResume(original -> compensate(reservation.getId(),reserved)
              .then(Mono.error(original)));
    });
  }

  private Mono<ReservationItem> decrement(ReservationItem item) {

    Query enoughStock = Query.query(Criteria.where("_id")
        .is(item.getIngredientId())
        .and("available").is(true)
        .and("stockOnHand").gte(item.getQuantity()));
    Update decrement = new Update().inc("stockOnHand",-item.getQuantity());

    return mongo.updateFirst(enoughStock,decrement,Ingredient.class)
        .flatMap(result -> {
          if (result.getModifiedCount() != 1) {
            return Mono.error(ApiException.conflict(
                "INSUFFICIENT_STOCK",
                "Insufficient stock for ingredient "
                    + item.getIngredientId() + "; requested=" + item.getQuantity()));
          }

          Query depleted = Query.query(Criteria.where("_id")
              .is(item.getIngredientId()).and("stockOnHand").is(0));
          return mongo.updateFirst(
                  depleted,Update.update("available",false),Ingredient.class)
              .thenReturn(item);
        });
  }

  private Mono<InventoryReservation> markReserved(String reservationId) {

    Query pending = Query.query(Criteria.where("_id").is(reservationId)
        .and("status").is(Status.PENDING));
    return mongo.findAndModify(
            pending,Update.update("status",Status.RESERVED),
            FindAndModifyOptions.options().returnNew(true),
            InventoryReservation.class)
        .switchIfEmpty(Mono.error(ApiException.conflict(
            "RESERVATION_STATE_CONFLICT","Reservation state changed unexpectedly.")));
  }

  private Mono<InventoryReservation> existingReservation(String reservationId) {

    return mongo.findById(reservationId,InventoryReservation.class)
        .switchIfEmpty(Mono.error(ApiException.conflict(
            "RESERVATION_STATE_CONFLICT","Reservation already exists.")))
        .flatMap(existing -> {
          if (existing.getStatus() == Status.RESERVED) {
            return Mono.just(existing);
          }
          String code = existing.getStatus() == Status.PENDING
              ? "RESERVATION_IN_PROGRESS" : "RESERVATION_ALREADY_RELEASED";
          return Mono.error(ApiException.conflict(
              code,"Reservation cannot be applied again."));
        });
  }

  private Mono<Void> compensate(
      String reservationId,List<ReservationItem> reserved) {

    Query pending = Query.query(Criteria.where("_id").is(reservationId)
        .and("status").is(Status.PENDING));
    return releaseItems(reserved)
        .then(mongo.updateFirst(
            pending,Update.update("status",Status.RELEASED),
            InventoryReservation.class))
        .then();
  }

  private Mono<Void> releaseItems(List<ReservationItem> reserved) {

    List<ReservationItem> reverse = new ArrayList<>(
        reserved != null ? reserved : Collections.emptyList());
    Collections.reverse(reverse);

    return Flux.fromIterable(reverse)
        .concatMap(item -> mongo.updateFirst(
            Query.query(Criteria.where("_id").is(item.getIngredientId())),
            new Update().inc("stockOnHand",item.getQuantity())
                .set("available",true),
            Ingredient.class))
        .then();
  }

  private List<ReservationItem> aggregate(TacoOrder orderDraft) {

    Map<String,Integer> required = new TreeMap<>();
    List<OrderItem> orderItems = orderDraft.getItems() != null
        ? orderDraft.getItems() : Collections.emptyList();

    for (OrderItem line : orderItems) {
      if (line == null || line.getTaco() == null || line.getQuantity() < 1) {
        throw ApiException.conflict(
            "INVALID_RESERVATION","Reservation contains an invalid order line.");
      }
      List<Ingredient> ingredients = line.getTaco().getIngredients() != null
          ? line.getTaco().getIngredients() : Collections.emptyList();
      for (Ingredient ingredient : ingredients) {
        if (ingredient == null || ingredient.getId() == null) {
          throw ApiException.conflict(
              "INVALID_RESERVATION","Reservation contains an invalid ingredient.");
        }
        required.merge(ingredient.getId(),line.getQuantity(),Integer::sum);
      }
    }

    return required.entrySet().stream()
        .map(entry -> new ReservationItem(entry.getKey(),entry.getValue()))
        .collect(Collectors.toList());
  }
}
