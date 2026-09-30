package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import tacos.web.api.dto.ApiDtos.OrderCreateRequest;
import tacos.web.api.mapper.ApiMapper;

public class OrderRequestHasherTest {

  private final ObjectMapper json = new ObjectMapper();
  private final OrderRequestHasher hasher = new OrderRequestHasher();

  @Test
  public void shouldHashCanonicalRequestInsteadOfJsonPropertyOrder()
      throws Exception {
    String first = "{\"deliveryName\":\"Ana\",\"deliveryStreet\":\"One\","
        + "\"deliveryCity\":\"AGS\",\"deliveryState\":\"AG\","
        + "\"deliveryZip\":\"20000\",\"paymentMethodId\":\"PAY-1\","
        + "\"couponCode\":\" save10 \",\"items\":[{\"quantity\":2,"
        + "\"taco\":{\"name\":\"Safe Taco\","
        + "\"ingredientIds\":[\"FLTO\",\"CHED\"]}}]}";
    String reordered = "{\"items\":[{\"taco\":{"
        + "\"ingredientIds\":[\"FLTO\",\"CHED\"],"
        + "\"name\":\"Safe Taco\"},\"quantity\":2}],"
        + "\"couponCode\":\"SAVE10\",\"paymentMethodId\":\"PAY-1\","
        + "\"deliveryZip\":\"20000\",\"deliveryState\":\"AG\","
        + "\"deliveryCity\":\"AGS\",\"deliveryStreet\":\"One\","
        + "\"deliveryName\":\"Ana\"}";

    OrderCreateRequest requestA = json.readValue(first,OrderCreateRequest.class);
    OrderCreateRequest requestB = json.readValue(reordered,OrderCreateRequest.class);

    assertEquals(
        hasher.hash(ApiMapper.toCommand(requestA)),
        hasher.hash(ApiMapper.toCommand(requestB)));

    requestB.getItems().get(0).setQuantity(3);
    assertNotEquals(
        hasher.hash(ApiMapper.toCommand(requestA)),
        hasher.hash(ApiMapper.toCommand(requestB)));
  }
}
