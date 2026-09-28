package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;

import tacos.web.api.dto.ApiDtos.OrderCreateRequest;
import tacos.web.api.dto.ApiDtos.OrderItemRequest;
import tacos.web.api.dto.ApiDtos.OrderResponse;
import tacos.web.api.dto.ApiDtos.OrderTacoRequest;
import tacos.web.api.dto.ApiDtos.PaymentMethodResponse;
import tacos.web.api.mapper.ApiMapper;
import tacos.web.api.mapper.ApiMapper.OrderCreateCommand;

public class DtoContractTest {


  /*
   * TC-08
   * Serialización negativa de
   * campos sensibles.
   */
  @Test
  public void shouldNotSerializeSensitiveOrderFields()
      throws Exception {

    TacoOrder order = new TacoOrder();

    order.setId("ORDER-1");
    order.setDeliveryName("Jose");
    User user = Mockito.mock(User.class);

    order.setUser(user);

    OrderResponse response = ApiMapper.toResponse(order);
    ObjectMapper mapper = new ObjectMapper();
    String json = mapper.writeValueAsString(response);

    assertFalse(json.contains("password"));
    assertFalse(json.contains("authorities"));
    assertFalse(json.contains("ccNumber"));
    assertFalse(json.contains("ccCVV"));
    assertFalse(json.contains("ccExpiration"));
    assertFalse(json.contains("\"user\""));
  }


  /*
   * TC-08
   * Request -> Command
   * Entity -> Response
   */
  @Test
  public void shouldMapRequestCommandAndEntityResponse() {

    OrderTacoRequest tacoRequest = new OrderTacoRequest();

    tacoRequest.setName("Test Taco");
    tacoRequest.setIngredientIds(Arrays.asList("FLTO"));

    OrderItemRequest itemRequest = new OrderItemRequest();
    itemRequest.setTaco(tacoRequest);
    itemRequest.setQuantity(2);

    OrderCreateRequest request = new OrderCreateRequest();

    request.setDeliveryName("Jose");
    request.setDeliveryCity("Aguascalientes");
    request.setItems(Arrays.asList(itemRequest));

    OrderCreateCommand command = ApiMapper.toCommand(request);

    assertEquals("Jose",command.getDeliveryName());
    assertEquals(1,command.getItems().size());
    assertEquals(2,command.getItems().get(0).getQuantity());
    assertEquals("FLTO",
        command
            .getItems()
            .get(0)
            .getTaco()
            .getIngredientIds()
            .get(0)
    );

    TacoOrder order = new TacoOrder();

    order.setId("ORDER-1");
    order.setDeliveryName("Jose");
    order.setDeliveryCity("Aguascalientes");
    order.setStatus(TacoOrder.Status.CREATED);

    Taco taco = new Taco();

    taco.setName("Test Taco");
    taco.setIngredients(
        Arrays.asList(new Ingredient("FLTO","Flour Tortilla",Ingredient.Type.WRAP)));

    order.addTaco(taco);

    OrderResponse response = ApiMapper.toResponse(order);

    assertEquals("ORDER-1",response.getId());
    assertEquals("Jose",response.getDeliveryName());
    assertEquals("CREATED",response.getStatus());
    assertEquals(1,response.getTacos().size());
    assertEquals("FLTO",
        response
            .getTacos()
            .get(0)
            .getIngredients()
            .get(0)
            .getId());
  }


  /*
   * TC-08
   * Mass assignment.
   *
   * El cliente intenta enviar
   * campos controlados por
   * el servidor.
   */
  @Test
  public void shouldIgnoreServerOwnedFields()
      throws Exception {

    String json =
      "{"
      + "\"deliveryName\":\"Jose\","
      + "\"id\":\"ORDER-HACK\","
      + "\"placedAt\":\"2000-01-01T00:00:00Z\","
      + "\"status\":\"PREPARING\","
      + "\"userId\":\"OTHER-USER\","
      + "\"total\":\"0.01\","
      + "\"items\":[{"
      + "\"quantity\":2,"
      + "\"unitPriceAtPurchase\":\"0.01\","
      + "\"subtotal\":\"0.02\","
      + "\"taco\":{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"]}}],"
      + "\"ccNumber\":\"TEST-PAN\","
      + "\"ccCVV\":\"TEST-CVV\""
      + "}";

    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    OrderCreateRequest request =
        mapper.readValue(json,OrderCreateRequest.class);

    OrderCreateCommand command = ApiMapper.toCommand(request);

    TacoOrder order = ApiMapper.toEntity(command);

    assertEquals("Jose",order.getDeliveryName());

    assertNull(order.getId());
    Date clientPlacedAt = Date.from(Instant.parse("2000-01-01T00:00:00Z"));

    assertNotEquals(clientPlacedAt,order.getPlacedAt());
    
    assertNull(order.getUser());

    assertEquals(0,new java.math.BigDecimal("0.00").compareTo(order.getTotal()));
    assertEquals(2,command.getItems().get(0).getQuantity());

    assertTrue(order.getStatus() == null || order.getStatus() == TacoOrder.Status.CREATED);
  }

  @Test
  public void shouldSerializeOnlySafePaymentFields()
      throws Exception {

    PaymentMethodResponse response =
        new PaymentMethodResponse("PAYMENT-1","VISA","1111");

    ObjectMapper mapper = new ObjectMapper();

    String json = mapper.writeValueAsString(response);

    assertTrue(json.contains("\"brand\":\"VISA\""));
    assertTrue(json.contains("\"last4\":\"1111\""));
    assertFalse(json.contains("paymentToken"));
    assertFalse(json.contains("cardNumber"));
    assertFalse(json.contains("cvv"));
  }
}
