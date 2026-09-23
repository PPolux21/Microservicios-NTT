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

import tacos.web.api.dto.ApiDtos.IngredientRequest;
import tacos.web.api.dto.ApiDtos.OrderCreateRequest;
import tacos.web.api.dto.ApiDtos.OrderResponse;
import tacos.web.api.dto.ApiDtos.OrderTacoRequest;

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
    /*
    order.setCcNumber("4111111111111111");
    order.setCcCVV("321");
    order.setCcExpiration("10/30");
    */
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

    IngredientRequest ingredient = new IngredientRequest();

    ingredient.setId("FLTO");
    ingredient.setName("Flour Tortilla");
    ingredient.setType(Ingredient.Type.WRAP);

    OrderTacoRequest tacoRequest = new OrderTacoRequest();

    tacoRequest.setName("Test Taco");
    tacoRequest.setIngredients(Arrays.asList(ingredient));

    OrderCreateRequest request = new OrderCreateRequest();

    request.setDeliveryName("Jose");
    request.setDeliveryCity("Aguascalientes");
    request.setTacos(Arrays.asList(tacoRequest));

    OrderCreateCommand command = ApiMapper.toCommand(request);

    assertEquals("Jose",command.getDeliveryName());
    assertEquals(1,command.getTacos().size());
    assertEquals("FLTO",
        command
            .getTacos()
            .get(0)
            .getIngredients()
            .get(0)
            .getId());

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
      + "\"total\":0,"
      + "\"userId\":\"OTHER-USER\","
      + "\"ccNumber\":\"4111111111111111\","
      + "\"ccCVV\":\"321\""
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

    assertTrue(order.getStatus() == null || order.getStatus() == TacoOrder.Status.CREATED);
  }
}