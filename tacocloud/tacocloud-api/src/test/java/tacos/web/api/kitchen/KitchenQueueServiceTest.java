package tacos.web.api.kitchen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tacos.actuator.TacoMetrics;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.TacoOrder.OrderItem;

public class KitchenQueueServiceTest {

  private KitchenProperties properties;
  private KitchenQueueService service;

  @BeforeEach
  public void setUp() {
    properties = properties(2,3,2,1);
    service = service(properties);
  }

  @Test
  public void shouldCalculateConfiguredDeterministicEta() {
    TacoOrder order = order(line(2,"A","B","C"));

    int first = service.estimatedPrepMinutes(order,1);
    int second = service.estimatedPrepMinutes(order,1);

    assertEquals(15,first);
    assertEquals(first,second);
  }

  @Test
  public void shouldIncreaseEtaWithQueueQuantityAndComplexity() {
    TacoOrder simple = order(line(1,"A"));
    TacoOrder moreQuantity = order(line(5,"A"));
    TacoOrder moreComplex = order(line(1,"A","B","C"));

    assertTrue(service.estimatedPrepMinutes(simple,5)
        > service.estimatedPrepMinutes(simple,1));
    assertTrue(service.estimatedPrepMinutes(moreQuantity,1)
        > service.estimatedPrepMinutes(simple,1));
    assertTrue(service.estimatedPrepMinutes(moreComplex,1)
        > service.estimatedPrepMinutes(simple,1));
  }

  @Test
  public void shouldUseChangedConfigurationWithoutHiddenConstants() {
    TacoOrder order = order(line(2,"A"));
    int original = service.estimatedPrepMinutes(order,0);
    KitchenQueueService changed = service(properties(2,3,7,1));

    assertEquals(original + 10,changed.estimatedPrepMinutes(order,0));
  }

  private KitchenQueueService service(KitchenProperties config) {
    return new KitchenQueueService(
        null,null,config,
        Clock.fixed(Instant.parse("2026-09-29T18:00:00Z"),ZoneOffset.UTC),
        new TacoMetrics(new SimpleMeterRegistry()));
  }

  private KitchenProperties properties(
      int base,int queued,int item,int complexity) {
    KitchenProperties config = new KitchenProperties();
    config.setStationId("STATION-TEST");
    config.getEta().setBaseMinutes(base);
    config.getEta().setMinutesPerQueuedOrder(queued);
    config.getEta().setMinutesPerItem(item);
    config.getEta().setMinutesPerComplexityPoint(complexity);
    return config;
  }

  private TacoOrder order(OrderItem... lines) {
    TacoOrder order = new TacoOrder();
    Arrays.stream(lines).forEach(order::addItem);
    return order;
  }

  private OrderItem line(int quantity,String... ingredientIds) {
    Taco taco = new Taco();
    taco.setName("Synthetic Taco");
    taco.setIngredients(Arrays.stream(ingredientIds)
        .map(id -> new Ingredient(
            id,"Ingredient " + id,Ingredient.Type.VEGGIES,
            BigDecimal.ONE,true,10,0))
        .collect(java.util.stream.Collectors.toList()));
    return new OrderItem(
        taco,quantity,BigDecimal.ONE,
        BigDecimal.valueOf(quantity));
  }
}
