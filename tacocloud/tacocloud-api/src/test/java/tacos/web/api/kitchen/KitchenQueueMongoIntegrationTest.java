package tacos.web.api.kitchen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.TacoOrder.OrderItem;
import tacos.TacoOrder.Status;
import tacos.web.api.OrderWorkflowService;
import tacos.web.api.dto.ApiDtos.KitchenOrderResponse;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

@SpringBootTest(
    classes=KitchenQueueMongoIntegrationTest.TestApplication.class,
    webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={
        "spring.data.mongodb.database=tc26_kitchen_queue_test",
        "spring.data.mongodb.auto-index-creation=true",
        "tacocloud.kitchen.station-id=STATION-A",
        "tacocloud.kitchen.eta.base-minutes=2",
        "tacocloud.kitchen.eta.minutes-per-queued-order=3",
        "tacocloud.kitchen.eta.minutes-per-item=2",
        "tacocloud.kitchen.eta.minutes-per-complexity-point=1"
    })
public class KitchenQueueMongoIntegrationTest {

  @Autowired
  private ReactiveMongoTemplate mongo;

  @Autowired
  private KitchenQueueService stationA;

  @Autowired
  private KitchenProperties stationAProperties;

  @Autowired
  private Clock clock;

  @MockBean
  private OrderWorkflowService workflow;

  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  public void clean() {
    mongo.remove(new Query(),TacoOrder.class).block();
  }

  @Test
  public void shouldClaimSingleOrderOnlyOnceWithTwoConcurrentStations() {
    KitchenQueueService stationB = service("STATION-B");
    Authentication cookA = kitchen("cook-a");
    Authentication cookB = kitchen("cook-b");

    Mono<List<Signal<KitchenOrderResponse>>> scenario = mongo
        .insert(order("ORDER-A",Instant.parse("2026-09-29T10:00:00Z"),Status.CREATED))
        .then(Mono.zip(
            stationA.claimNext(cookA).materialize(),
            stationB.claimNext(cookB).materialize()))
        .map(signals -> Arrays.asList(signals.getT1(),signals.getT2()));

    StepVerifier.create(scenario)
        .assertNext(signals -> {
          assertEquals(1,signals.stream().filter(Signal::isOnNext).count());
          assertEquals(1,signals.stream().filter(Signal::isOnComplete).count());
          KitchenOrderResponse response = signals.stream()
              .filter(Signal::isOnNext).findFirst().get().get();
          String serialized = json.valueToTree(response).toString();
          assertFalse(serialized.contains("deliveryStreet"));
          assertFalse(serialized.contains("deliveryZip"));
          assertFalse(serialized.contains("payment"));
          assertFalse(serialized.contains("userId"));
          assertFalse(serialized.contains("password"));
        })
        .verifyComplete();

    TacoOrder claimed = mongo.findById("ORDER-A",TacoOrder.class).block();
    assertNotNull(claimed);
    assertEquals(Status.ACCEPTED,claimed.getStatus());
    assertNotNull(claimed.getStationId());
    assertNotNull(claimed.getCookId());
    assertEquals("OWNER-1",claimed.getUserId());
    assertEquals(1,claimed.getStatusHistory().size());
    assertEquals("Claimed by kitchen",claimed.getStatusHistory().get(0).getReason());
    assertEquals("KITCHEN_API",claimed.getStatusHistory().get(0).getOrigin().name());
    assertNotNull(claimed.getVersion());
    verify(workflow,atLeastOnce()).validateTransition(
        org.mockito.ArgumentMatchers.eq(Status.CREATED),
        org.mockito.ArgumentMatchers.eq(Status.ACCEPTED),any(Authentication.class));

  }

  @Test
  public void shouldGiveDifferentOrdersToTwoConcurrentStations() {
    KitchenQueueService stationB = service("STATION-B");
    Mono<List<Signal<KitchenOrderResponse>>> scenario = Flux.just(
            order("ORDER-A",Instant.parse("2026-09-29T10:00:00Z"),Status.CREATED),
            order("ORDER-B",Instant.parse("2026-09-29T10:05:00Z"),Status.CREATED))
        .concatMap(mongo::insert)
        .then(Mono.zip(
            stationA.claimNext(kitchen("cook-a")).materialize(),
            stationB.claimNext(kitchen("cook-b")).materialize()))
        .map(signals -> Arrays.asList(signals.getT1(),signals.getT2()));

    StepVerifier.create(scenario)
        .assertNext(signals -> {
          assertTrue(signals.stream().allMatch(Signal::isOnNext));
          assertNotEquals(
              signals.get(0).get().getOrderId(),signals.get(1).get().getOrderId());
        })
        .verifyComplete();
  }

  @Test
  public void shouldUseStableFifoAndFilterAtMongo() {
    Date sameTime = Date.from(Instant.parse("2026-09-29T10:05:00Z"));
    TacoOrder ignored = order(
        "ORDER-IGNORED",Instant.parse("2026-09-29T09:00:00Z"),Status.ACCEPTED);
    TacoOrder first = order(
        "ORDER-FIRST",Instant.parse("2026-09-29T10:00:00Z"),Status.CREATED);
    TacoOrder tieB = order("ORDER-B",sameTime.toInstant(),Status.CREATED);
    TacoOrder tieA = order("ORDER-A",sameTime.toInstant(),Status.CREATED);

    Mono<List<KitchenOrderResponse>> scenario = Flux.just(
            ignored,tieB,first,tieA)
        .concatMap(mongo::insert)
        .thenMany(stationA.queue(kitchen("cook-a")))
        .collectList();

    StepVerifier.create(scenario)
        .assertNext(queue -> {
          assertEquals(Arrays.asList("ORDER-FIRST","ORDER-A","ORDER-B"),
              queue.stream().map(KitchenOrderResponse::getOrderId)
                  .collect(Collectors.toList()));
          assertTrue(queue.get(1).getEstimatedPrepMinutes()
              > queue.get(0).getEstimatedPrepMinutes());
          assertTrue(queue.get(2).getEstimatedPrepMinutes()
              > queue.get(1).getEstimatedPrepMinutes());
        })
        .verifyComplete();
  }

  @Test
  public void shouldPreventSameStationFromClaimingTwoActiveOrders() {
    Mono<List<Signal<KitchenOrderResponse>>> scenario = Flux.just(
            order("ORDER-A",Instant.parse("2026-09-29T10:00:00Z"),Status.CREATED),
            order("ORDER-B",Instant.parse("2026-09-29T10:01:00Z"),Status.CREATED))
        .concatMap(mongo::insert)
        .then(Mono.zip(
            stationA.claimNext(kitchen("cook-a")).materialize(),
            stationA.claimNext(kitchen("cook-a")).materialize()))
        .map(signals -> Arrays.asList(signals.getT1(),signals.getT2()));

    StepVerifier.create(scenario)
        .assertNext(signals -> {
          assertEquals(1,signals.stream().filter(Signal::isOnNext).count());
          List<Throwable> errors = signals.stream().filter(Signal::isOnError)
              .map(Signal::getThrowable).collect(Collectors.toList());
          assertEquals(1,errors.size());
          assertTrue(errors.get(0) instanceof ApiException);
          assertEquals("KITCHEN_STATION_BUSY",
              ((ApiException) errors.get(0)).getCode());
        })
        .verifyComplete();
  }

  @Test
  public void shouldClaimInFifoOrderIncludingIdTieBreak() {
    KitchenQueueService stationB = service("STATION-B");
    KitchenQueueService stationC = service("STATION-C");
    Date same = Date.from(Instant.parse("2026-09-29T10:00:00Z"));

    Mono<List<String>> scenario = Flux.just(
            order("ORDER-B",same.toInstant(),Status.CREATED),
            order("ORDER-A",same.toInstant(),Status.CREATED),
            order("ORDER-C",Instant.parse("2026-09-29T10:05:00Z"),Status.CREATED))
        .concatMap(mongo::insert)
        .then(stationA.claimNext(kitchen("cook-a")))
        .flatMap(first -> stationB.claimNext(kitchen("cook-b"))
            .flatMap(second -> stationC.claimNext(kitchen("cook-c"))
                .map(third -> Arrays.asList(
                    first.getOrderId(),second.getOrderId(),third.getOrderId()))));

    StepVerifier.create(scenario)
        .expectNext(Arrays.asList("ORDER-A","ORDER-B","ORDER-C"))
        .verifyComplete();
  }

  private KitchenQueueService service(String stationId) {
    KitchenProperties config = new KitchenProperties();
    config.setStationId(stationId);
    config.setEta(stationAProperties.getEta());
    return new KitchenQueueService(mongo,workflow,config,clock);
  }

  private Authentication kitchen(String name) {
    return new UsernamePasswordAuthenticationToken(
        name,"n/a",Collections.singletonList(
            new SimpleGrantedAuthority("ROLE_KITCHEN")));
  }

  private TacoOrder order(String id,Instant placedAt,Status status) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setUserId("OWNER-1");
    order.setPlacedAt(Date.from(placedAt));
    order.setStatus(status);
    order.setDeliveryStreet("Sensitive Street");
    order.setDeliveryZip("99999");
    order.addItem(line(1,"A","B"));
    return order;
  }

  private OrderItem line(int quantity,String... ingredientIds) {
    Taco taco = new Taco();
    taco.setName("Synthetic Taco");
    taco.setIngredients(Arrays.stream(ingredientIds)
        .map(id -> new Ingredient(
            id,"Ingredient " + id,Ingredient.Type.VEGGIES,
            BigDecimal.ONE,true,10,0))
        .collect(Collectors.toList()));
    return new OrderItem(
        taco,quantity,BigDecimal.ONE,BigDecimal.valueOf(quantity));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({KitchenQueueService.class,KitchenProperties.class})
  static class TestApplication {

    @Bean
    Clock clock() {
      return Clock.fixed(
          Instant.parse("2026-09-29T18:00:00Z"),ZoneOffset.UTC);
    }
  }
}
