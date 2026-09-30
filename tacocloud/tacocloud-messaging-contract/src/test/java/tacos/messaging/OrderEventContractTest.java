package tacos.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import tacos.messaging.OrderEventPayload.IngredientSnapshot;
import tacos.messaging.OrderEventPayload.OrderItemSnapshot;

public class OrderEventContractTest {

  private final ObjectMapper json = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  public void shouldRoundTripAndMatchOrderCreatedV1Snapshot() throws Exception {
    OrderEvent event = fixedEvent();

    String serialized = json.writerWithDefaultPrettyPrinter()
        .writeValueAsString(event);
    OrderEvent restored = json.readValue(serialized,OrderEvent.class);

    assertEquals(event.getEventId(),restored.getEventId());
    assertEquals(OrderEventType.ORDER_CREATED,restored.getEventType());
    assertEquals(OrderEvent.CURRENT_VERSION,restored.getVersion());
    assertEquals(event.getOccurredAt(),restored.getOccurredAt());
    assertEquals("corr-123",restored.getCorrelationId());
    assertEquals("ORDER-123",restored.getPayload().getOrderId());
    assertEquals(2,restored.getPayload().getItems().get(0).getQuantity());

    try (InputStream expected = getClass().getResourceAsStream(
        "/contracts/order-created-v1.json")) {
      assertNotNull(expected);
      assertEquals(json.readTree(expected),json.readTree(serialized));
    }
  }

  @Test
  public void shouldIgnoreCompatibleFutureFields() throws Exception {
    JsonNode tree = json.valueToTree(fixedEvent());
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree)
        .put("futureField","future-value");
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree.get("payload"))
        .put("futurePayloadField","future-value");

    OrderEvent restored = json.treeToValue(tree,OrderEvent.class);

    assertEquals("ORDER-123",restored.getPayload().getOrderId());
    assertEquals(OrderEvent.CURRENT_VERSION,restored.getVersion());
  }

  @Test
  public void shouldExcludeSensitiveFieldsAndValues() throws Exception {
    String serialized = json.writeValueAsString(fixedEvent()).toLowerCase();

    for (String forbidden : Arrays.asList(
        "pan","ccnumber","cvv","cccvv","ccexpiration",
        "paymenttoken","password","passwordhash","4111111111111111")) {
      assertFalse(serialized.contains(forbidden),forbidden);
    }
  }

  @Test
  public void shouldGenerateDistinctCompleteEventIdentities() {
    OrderEventPayload payload = fixedEvent().getPayload();
    OrderEvent first = OrderEvent.create(
        OrderEventType.ORDER_CREATED,"corr-generated",payload);
    OrderEvent second = OrderEvent.create(
        OrderEventType.ORDER_CREATED,"corr-generated",payload);

    assertNotNull(first.getEventId());
    assertNotEquals(first.getEventId(),second.getEventId());
    assertNotNull(first.getOccurredAt());
    assertEquals(OrderEvent.CURRENT_VERSION,first.getVersion());
    assertEquals("corr-generated",first.getCorrelationId());
  }

  private OrderEvent fixedEvent() {
    OrderItemSnapshot item = new OrderItemSnapshot("Safe Taco",2,Arrays.asList(
        new IngredientSnapshot("FLTO","Flour Tortilla"),
        new IngredientSnapshot("CARN","Carnitas")));
    OrderEventPayload payload = new OrderEventPayload(
        "ORDER-123","CREATED",null,
        Instant.parse("2026-09-29T17:59:00Z"),
        Collections.singletonList(item),null,null,null);
    return new OrderEvent(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        OrderEventType.ORDER_CREATED,1,
        Instant.parse("2026-09-29T18:00:00Z"),"corr-123",payload);
  }
}
