package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.web.api.OrderIdempotencyService.PlacementResult;
import tacos.web.api.error.ApiExceptionHandler;
import tacos.web.api.mapper.ApiMapper.OrderCreateCommand;

public class OpenApiContractTest {

  private static final Path SPEC = Paths.get("..","..","docs","openapi.yaml")
      .normalize().toAbsolutePath();

  private final ObjectMapper json = new ObjectMapper();
  private OpenAPI api;
  private OrderService orderService;
  private WebTestClient client;

  @BeforeEach
  public void setUp() {
    ParseOptions options = new ParseOptions();
    options.setResolve(false);
    options.setResolveFully(false);
    SwaggerParseResult parsed = new OpenAPIV3Parser().readLocation(
        SPEC.toString(),null,options);
    assertTrue(parsed.getMessages() == null || parsed.getMessages().isEmpty(),
        () -> "OpenAPI validation errors: " + parsed.getMessages());
    api = parsed.getOpenAPI();
    assertNotNull(api,"OpenAPI document was not parsed");

    OrderRepository repository = mock(OrderRepository.class);
    orderService = mock(OrderService.class);
    OrderWorkflowService workflow = mock(OrderWorkflowService.class);
    OrderApiController controller = new OrderApiController(
        repository,orderService,workflow);
    ObjectMapper httpJson = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    client = MockMvcWebTestClient.bindToController(controller)
        .controllerAdvice(new ApiExceptionHandler())
        .messageConverters(new MappingJackson2HttpMessageConverter(httpJson))
        .build();
  }

  @Test
  public void shouldValidateOpenApiAndDocumentExactlyThePublicV1Paths() {
    assertEquals("3.0.3",api.getOpenapi());
    assertNotNull(api.getComponents().getSecuritySchemes().get("basicAuth"));

    Set<String> expected = new HashSet<>(Arrays.asList(
        "/api/v1/ingredients",
        "/api/v1/ingredients/{id}",
        "/api/v1/admin/ingredients/{id}/catalog",
        "/api/v1/admin/ingredients/{id}/stock-adjustments",
        "/api/v1/tacos",
        "/api/v1/tacos/today",
        "/api/v1/tacos/validate",
        "/api/v1/tacos/top",
        "/api/v1/tacos/{id}",
        "/api/v1/tacos/{id}/classification",
        "/api/v1/tacos/{id}/rating",
        "/api/v1/users/me/favorites",
        "/api/v1/users/me/favorites/{tacoId}",
        "/api/v1/users/me/orders",
        "/api/v1/users/me/orders/{orderId}",
        "/api/v1/admin/orders",
        "/api/v1/orders",
        "/api/v1/orders/quote",
        "/api/v1/orders/{orderId}/reorder",
        "/api/v1/orders/{orderId}/status",
        "/api/v1/orders/{orderId}/cancel",
        "/api/v1/orders/fromEmail",
        "/api/v1/orders/{orderId}",
        "/api/v1/kitchen/queue",
        "/api/v1/kitchen/orders/claim",
        "/api/v1/admin/announcements",
        "/api/v1/admin/announcements/{announcementId}"));

    assertEquals(expected,api.getPaths().keySet());
    assertTrue(api.getPaths().keySet().stream()
        .allMatch(path -> path.startsWith("/api/v1/")));
  }

  @Test
  public void shouldMatchPostOrderCreatedReplayAndProblemContracts()
      throws Exception {
    TacoOrder order = order("ORDER-35");
    when(orderService.createOrder(
        any(OrderCreateCommand.class),eq("order-key-0035"),
        nullable(Authentication.class)))
        .thenReturn(Mono.just(new PlacementResult(order,false)))
        .thenReturn(Mono.just(new PlacementResult(order,true)));

    EntityExchangeResult<byte[]> created = postOrder("/api/v1/orders")
        .expectStatus().isCreated()
        .expectHeader().valueMatches("Location",".*/api/v1/orders/ORDER-35")
        .expectHeader().contentType(MediaType.APPLICATION_JSON)
        .expectBody().returnResult();
    assertDocumentedStatus("201");
    assertMatches(json.readTree(created.getResponseBody()),
        responseSchema("201"));

    EntityExchangeResult<byte[]> replay = postOrder("/api/v1/orders")
        .expectStatus().isOk()
        .expectHeader().contentType(MediaType.APPLICATION_JSON)
        .expectBody().returnResult();
    assertDocumentedStatus("200");
    assertMatches(json.readTree(replay.getResponseBody()),
        responseSchema("200"));

    EntityExchangeResult<byte[]> invalid = client.post()
        .uri("/api/v1/orders")
        .header("Idempotency-Key","order-key-invalid")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .exchange()
        .expectStatus().isBadRequest()
        .expectHeader().contentType("application/problem+json")
        .expectBody().returnResult();
    assertMatches(json.readTree(invalid.getResponseBody()),
        problemSchema("400"));
  }

  @Test
  public void shouldKeepLegacyOrderAliasAndDetectIncompatibleResponses()
      throws Exception {
    TacoOrder order = order("ORDER-LEGACY");
    when(orderService.createOrder(
        any(OrderCreateCommand.class),eq("order-key-legacy"),
        nullable(Authentication.class)))
        .thenReturn(Mono.just(new PlacementResult(order,true)));

    client.post().uri("/api/orders")
        .header("Idempotency-Key","order-key-legacy")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(validOrderRequest())
        .exchange()
        .expectStatus().isOk()
        .expectBody().jsonPath("$.id").isEqualTo("ORDER-LEGACY");

    ObjectNode incompatible = (ObjectNode) json.valueToTree(
        tacos.web.api.mapper.ApiMapper.toResponse(order));
    incompatible.remove("id");
    assertThrows(AssertionError.class,
        () -> assertMatches(incompatible,responseSchema("200")));
    assertFalse(api.getPaths().get("/api/v1/orders").getPost()
        .getResponses().containsKey("202"));
  }

  @Test
  public void shouldExcludeSensitiveAndPersistenceSchemas() {
    Set<String> propertyNames = new HashSet<>();
    api.getComponents().getSchemas().values()
        .forEach(schema -> collectPropertyNames(schema,propertyNames));
    Set<String> forbidden = new HashSet<>(Arrays.asList(
        "password","passwordhash","ccnumber","cccvv","cvv","pan",
        "authorities","paymenttoken"));
    propertyNames.forEach(name -> assertFalse(
        forbidden.contains(name.toLowerCase()),"Sensitive field: " + name));
    assertFalse(api.getComponents().getSchemas().containsKey("TacoOrder"));
    assertFalse(api.getComponents().getSchemas().containsKey("User"));
    assertFalse(api.getComponents().getSchemas().containsKey("PaymentMethod"));
  }

  private WebTestClient.ResponseSpec postOrder(String path) {
    return client.post().uri(path)
        .header("Idempotency-Key","order-key-0035")
        .header("X-Correlation-Id","contract-test-35")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(validOrderRequest())
        .exchange();
  }

  private Map<String,Object> validOrderRequest() {
    Map<String,Object> taco = new java.util.LinkedHashMap<>();
    taco.put("name","Contract Taco");
    taco.put("ingredientIds",Arrays.asList("FLTO","LETC"));
    Map<String,Object> item = new java.util.LinkedHashMap<>();
    item.put("taco",taco);
    item.put("quantity",1);
    Map<String,Object> request = new java.util.LinkedHashMap<>();
    request.put("deliveryName","Contract Client");
    request.put("deliveryStreet","Test Street 1");
    request.put("deliveryCity","Aguascalientes");
    request.put("deliveryState","AGS");
    request.put("deliveryZip","20000");
    request.put("paymentMethodId","PAYMENT-1");
    request.put("items",Collections.singletonList(item));
    return request;
  }

  private TacoOrder order(String id) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setVersion(0L);
    order.setDeliveryName("Contract Client");
    order.setDeliveryStreet("Test Street 1");
    order.setDeliveryCity("Aguascalientes");
    order.setDeliveryState("AGS");
    order.setDeliveryZip("20000");
    order.setPlacedAt(new Date(1790791200000L));
    order.setStatus(TacoOrder.Status.CREATED);
    order.setSubtotal(new BigDecimal("20.00"));
    order.setDiscountAmount(new BigDecimal("0.00"));
    order.setTotal(new BigDecimal("20.00"));
    order.setCurrency("MXN");
    return order;
  }

  private void assertDocumentedStatus(String status) {
    assertTrue(api.getPaths().get("/api/v1/orders").getPost()
        .getResponses().containsKey(status),"Undocumented HTTP status " + status);
  }

  private Schema<?> responseSchema(String status) {
    ApiResponse response = api.getPaths().get("/api/v1/orders").getPost()
        .getResponses().get(status);
    return response.getContent().get("application/json").getSchema();
  }

  private Schema<?> problemSchema(String status) {
    ApiResponse operationResponse = api.getPaths().get("/api/v1/orders")
        .getPost().getResponses().get(status);
    ApiResponse response = operationResponse.get$ref() != null
        ? api.getComponents().getResponses().get(refName(operationResponse.get$ref()))
        : operationResponse;
    return response.getContent().get("application/problem+json").getSchema();
  }

  private void assertMatches(JsonNode node,Schema<?> declared) {
    Schema<?> schema = resolve(declared);
    if (node == null || node.isMissingNode()) {
      throw new AssertionError("Response body is missing");
    }
    if (node.isNull()) {
      if (!Boolean.TRUE.equals(schema.getNullable())) {
        throw new AssertionError("Non-nullable schema received null");
      }
      return;
    }
    if (schema instanceof ArraySchema || "array".equals(schema.getType())) {
      if (!node.isArray()) {
        throw new AssertionError("Expected array response");
      }
      for (JsonNode item : node) {
        assertMatches(item,schema.getItems());
      }
      return;
    }
    if ("object".equals(schema.getType()) || schema.getProperties() != null) {
      if (!node.isObject()) {
        throw new AssertionError("Expected object response");
      }
      if (schema.getRequired() != null) {
        for (String required : schema.getRequired()) {
          if (!node.has(required)) {
            throw new AssertionError("Missing required field: " + required);
          }
        }
      }
      if (schema.getProperties() != null) {
        for (Map.Entry<String,Schema> property
            : ((Map<String,Schema>) schema.getProperties()).entrySet()) {
          if (node.has(property.getKey())) {
            assertMatches(node.get(property.getKey()),property.getValue());
          }
        }
      }
      return;
    }
    String type = schema.getType();
    if ("string".equals(type) && !node.isTextual()
        || "integer".equals(type) && !node.isIntegralNumber()
        || "number".equals(type) && !node.isNumber()
        || "boolean".equals(type) && !node.isBoolean()) {
      throw new AssertionError(
          "Incompatible JSON type for " + type + ": " + node);
    }
  }

  private Schema<?> resolve(Schema<?> schema) {
    if (schema.get$ref() == null) {
      return schema;
    }
    Schema<?> resolved = api.getComponents().getSchemas().get(
        refName(schema.get$ref()));
    assertNotNull(resolved,"Unresolved schema " + schema.get$ref());
    return resolved;
  }

  private String refName(String ref) {
    return ref.substring(ref.lastIndexOf('/') + 1);
  }

  @SuppressWarnings("unchecked")
  private void collectPropertyNames(Schema<?> declared,Set<String> names) {
    Schema<?> schema = resolve(declared);
    if (schema.getProperties() != null) {
      ((Map<String,Schema>) schema.getProperties()).forEach((name,property) -> {
        names.add(name);
        collectPropertyNames(property,names);
      });
    }
    if (schema.getItems() != null) {
      collectPropertyNames(schema.getItems(),names);
    }
    if (schema.getAllOf() != null) {
      schema.getAllOf().forEach(part -> collectPropertyNames(part,names));
    }
  }
}
