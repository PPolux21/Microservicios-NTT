package tacos.web.api.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.TacoOrder.OrderItem;
import tacos.User;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventType;

public class OrderEventMapperTest {

  @Test
  public void shouldMapRealisticOrderCreatedSnapshotWithoutSensitiveData()
      throws Exception {
    Ingredient tortilla = new Ingredient(
        "FLTO","Flour Tortilla",Type.WRAP,BigDecimal.ONE,true,20,5);
    Ingredient carnitas = new Ingredient(
        "CARN","Carnitas",Type.PROTEIN,BigDecimal.TEN,true,20,5);
    Taco taco = new Taco();
    taco.setName("Kitchen Taco");
    taco.setIngredients(Arrays.asList(tortilla,carnitas));

    TacoOrder order = new TacoOrder();
    order.setId("ORDER-27");
    order.setStatus(TacoOrder.Status.CREATED);
    order.setDeliveryName("Sensitive Delivery Name");
    order.setDeliveryStreet("Sensitive Street");
    User owner = new User("owner","{bcrypt}sensitive-password","Owner",
        "Sensitive Street","City","State","00000","555","owner@test");
    owner.setId("USER-27");
    order.setUser(owner);
    order.addItem(new OrderItem(
        taco,2,BigDecimal.valueOf(11),BigDecimal.valueOf(22)));

    OrderEvent event = OrderEventMapper.orderCreated(order,"corr-27");

    assertEquals(OrderEventType.ORDER_CREATED,event.getEventType());
    assertNotNull(event.getEventId());
    assertNotNull(event.getOccurredAt());
    assertEquals("corr-27",event.getCorrelationId());
    assertEquals("ORDER-27",event.getPayload().getOrderId());
    assertEquals("CREATED",event.getPayload().getStatus());
    assertEquals(2,event.getPayload().getItems().get(0).getQuantity());
    assertEquals("Kitchen Taco",
        event.getPayload().getItems().get(0).getTacoName());
    assertEquals("FLTO",event.getPayload().getItems().get(0)
        .getIngredients().get(0).getIngredientId());

    String json = new ObjectMapper().findAndRegisterModules()
        .writeValueAsString(event).toLowerCase();
    for (String forbidden : Arrays.asList(
        "delivery","sensitive street","user-27","password",
        "paymenttoken","ccnumber","cvv")) {
      assertFalse(json.contains(forbidden),forbidden);
    }
  }
}
