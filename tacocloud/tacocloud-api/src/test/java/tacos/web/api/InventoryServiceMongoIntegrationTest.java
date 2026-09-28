package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.InventoryReservation;
import tacos.InventoryReservation.Status;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.TacoOrder.OrderItem;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

@SpringBootTest(
    classes=InventoryServiceMongoIntegrationTest.TestApplication.class,
    webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties="spring.data.mongodb.database=tc16_inventory_test")
public class InventoryServiceMongoIntegrationTest {

  @Autowired
  private InventoryService inventoryService;

  @Autowired
  private ReactiveMongoTemplate mongo;

  @Test
  public void shouldAllowOnlyOneConcurrentBuyerForStockOne() {

    Mono<Signal<InventoryReservation>> first = inventoryService
        .reserve(order("ORDER-A",line(1,"ONLY"))).materialize();
    Mono<Signal<InventoryReservation>> second = inventoryService
        .reserve(order("ORDER-B",line(1,"ONLY"))).materialize();

    Mono<ConcurrentResult> scenario = clean()
        .then(mongo.insert(ingredient("ONLY",1,true)))
        .then(Mono.zip(first,second))
        .flatMap(signals -> mongo.findById("ONLY",Ingredient.class)
            .map(stock -> new ConcurrentResult(
                Arrays.asList(signals.getT1(),signals.getT2()),stock)));

    StepVerifier.create(scenario)
        .assertNext(result -> {
          long successes = result.signals.stream().filter(Signal::isOnNext).count();
          List<Throwable> failures = result.signals.stream()
              .filter(Signal::isOnError).map(Signal::getThrowable)
              .collect(Collectors.toList());

          assertEquals(1,successes);
          assertEquals(1,failures.size());
          assertTrue(failures.get(0) instanceof ApiException);
          assertEquals("INSUFFICIENT_STOCK",
              ((ApiException) failures.get(0)).getCode());
          assertEquals(0,result.ingredient.getStockOnHand());
          assertTrue(!result.ingredient.isAvailable());
          assertTrue(result.ingredient.getStockOnHand() >= 0);
        })
        .verifyComplete();
  }

  @Test
  public void shouldCompensateOnlyReservedIngredientsInStableOrder() {

    TacoOrder draft = order("ORDER-COMP",line(1,"C","B","A"));

    Mono<CompensationResult> scenario = clean()
        .thenMany(reactor.core.publisher.Flux.just(
            ingredient("A",10,true),ingredient("B",10,true),
            ingredient("C",0,false)).concatMap(mongo::insert))
        .then(inventoryService.reserve(draft).materialize())
        .flatMap(signal -> Mono.zip(
            mongo.findById("A",Ingredient.class),
            mongo.findById("B",Ingredient.class),
            mongo.findById("C",Ingredient.class),
            mongo.findById("ORDER-COMP",InventoryReservation.class))
            .map(values -> new CompensationResult(
                signal,values.getT1(),values.getT2(),values.getT3(),values.getT4())));

    StepVerifier.create(scenario)
        .assertNext(result -> {
          assertTrue(result.signal.isOnError());
          assertEquals("INSUFFICIENT_STOCK",
              ((ApiException) result.signal.getThrowable()).getCode());
          assertEquals(10,result.a.getStockOnHand());
          assertEquals(10,result.b.getStockOnHand());
          assertEquals(0,result.c.getStockOnHand());
          assertTrue(Stream.of(result.a,result.b,result.c)
              .allMatch(item -> item.getStockOnHand() >= 0));
          assertEquals(Status.RELEASED,result.reservation.getStatus());
          assertEquals(Arrays.asList("A","B","C"),
              result.reservation.getItems().stream()
                  .map(item -> item.getIngredientId())
                  .collect(Collectors.toList()));
        })
        .verifyComplete();
  }

  @Test
  public void shouldAggregateAndReserveSameIdOnlyOnce() {

    TacoOrder draft = order(
        "ORDER-IDEMPOTENT",line(2,"SHARED"),line(3,"SHARED"));

    Mono<InventoryReservation> scenario = clean()
        .then(mongo.insert(ingredient("SHARED",10,true)))
        .then(inventoryService.reserve(draft))
        .then(inventoryService.reserve(draft))
        .flatMap(reservation -> mongo.findById("SHARED",Ingredient.class)
            .doOnNext(stock -> assertEquals(5,stock.getStockOnHand()))
            .thenReturn(reservation));

    StepVerifier.create(scenario)
        .assertNext(reservation -> {
          assertEquals(Status.RESERVED,reservation.getStatus());
          assertEquals(1,reservation.getItems().size());
          assertEquals(5,reservation.getItems().get(0).getQuantity());
        })
        .verifyComplete();
  }

  @Test
  public void shouldReleaseOnlyOnce() {

    TacoOrder draft = order("ORDER-RELEASE",line(2,"REL"));

    Mono<ReleaseResult> scenario = clean()
        .then(mongo.insert(ingredient("REL",10,true)))
        .then(inventoryService.reserve(draft))
        .then(inventoryService.release("ORDER-RELEASE"))
        .then(inventoryService.release("ORDER-RELEASE"))
        .then(Mono.zip(
            mongo.findById("REL",Ingredient.class),
            mongo.findById("ORDER-RELEASE",InventoryReservation.class)))
        .map(values -> new ReleaseResult(values.getT1(),values.getT2()));

    StepVerifier.create(scenario)
        .assertNext(result -> {
          assertEquals(10,result.ingredient.getStockOnHand());
          assertTrue(result.ingredient.isAvailable());
          assertTrue(result.ingredient.getStockOnHand() >= 0);
          assertEquals(Status.RELEASED,result.reservation.getStatus());
        })
        .verifyComplete();
  }

  @Test
  public void shouldNotReserveUnavailableIngredientWithStock() {

    Mono<Signal<InventoryReservation>> scenario = clean()
        .then(mongo.insert(ingredient("OFF",10,false)))
        .then(inventoryService.reserve(
            order("ORDER-OFF",line(1,"OFF"))).materialize());

    StepVerifier.create(scenario.flatMap(signal ->
        mongo.findById("OFF",Ingredient.class)
            .doOnNext(stock -> assertEquals(10,stock.getStockOnHand()))
            .thenReturn(signal)))
        .assertNext(signal -> {
          assertTrue(signal.isOnError());
          assertEquals("INSUFFICIENT_STOCK",
              ((ApiException) signal.getThrowable()).getCode());
        })
        .verifyComplete();
  }

  private Mono<Void> clean() {
    return mongo.remove(new Query(),InventoryReservation.class)
        .then(mongo.remove(new Query(),Ingredient.class))
        .then();
  }

  private Ingredient ingredient(String id,int stock,boolean available) {
    return new Ingredient(
        id,"Synthetic " + id,Ingredient.Type.VEGGIES,
        new BigDecimal("1.00"),available,stock,0);
  }

  private TacoOrder order(String id,OrderItem... items) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    Arrays.stream(items).forEach(order::addItem);
    return order;
  }

  private OrderItem line(int quantity,String... ingredientIds) {
    Taco taco = new Taco();
    taco.setName("Synthetic Taco");
    taco.setIngredients(Arrays.stream(ingredientIds)
        .map(id -> ingredient(id,1,true)).collect(Collectors.toList()));
    return new OrderItem(
        taco,quantity,new BigDecimal("1.00"),
        BigDecimal.valueOf(quantity).setScale(2));
  }

  private static class ConcurrentResult {
    private final List<Signal<InventoryReservation>> signals;
    private final Ingredient ingredient;

    private ConcurrentResult(
        List<Signal<InventoryReservation>> signals,Ingredient ingredient) {
      this.signals = signals;
      this.ingredient = ingredient;
    }
  }

  private static class CompensationResult {
    private final Signal<InventoryReservation> signal;
    private final Ingredient a;
    private final Ingredient b;
    private final Ingredient c;
    private final InventoryReservation reservation;

    private CompensationResult(
        Signal<InventoryReservation> signal,Ingredient a,Ingredient b,
        Ingredient c,InventoryReservation reservation) {
      this.signal = signal;
      this.a = a;
      this.b = b;
      this.c = c;
      this.reservation = reservation;
    }
  }

  private static class ReleaseResult {
    private final Ingredient ingredient;
    private final InventoryReservation reservation;

    private ReleaseResult(
        Ingredient ingredient,InventoryReservation reservation) {
      this.ingredient = ingredient;
      this.reservation = reservation;
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(InventoryService.class)
  static class TestApplication {
  }
}
