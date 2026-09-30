package tacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import javax.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import tacos.IdempotencyRecord;
import tacos.Ingredient.Type;
import tacos.InventoryReservation;
import tacos.User.Role;
import tacos.data.IdempotencyRecordRepository;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.data.outbox.OutboxEvent;
import tacos.data.outbox.OutboxEventRepository;

@Testcontainers
@SpringBootTest(
    classes={
        TacoCloudApplication.class,
        TacoCloudApplicationTests.HttpTestConfiguration.class
    },
    webEnvironment=WebEnvironment.RANDOM_PORT,
    properties={
        "spring.profiles.active=test",
        "spring.main.web-application-type=servlet",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.mongo.embedded.EmbeddedMongoAutoConfiguration",
        "spring.data.mongodb.auto-index-creation=true",
        "spring.boot.admin.client.enabled=false",
        "spring.jmx.enabled=false",
        "tacocloud.rating.min-votes=3",
        "tacocloud.rating.max-top-limit=50",
        "tacocloud.kitchen.station-id=tc36-station",
        "tacocloud.messaging.transport=noop",
        "tacocloud.outbox.publisher.enabled=false"
    })
public class TacoCloudApplicationTests {

  private static final String PASSWORD = "tc36-test-password";
  private static final String OWNER = "tc36-owner";
  private static final String OTHER = "tc36-other";
  private static final String ADMIN = "tc36-admin";
  private static final String KITCHEN = "tc36-kitchen";
  private static final String PAYMENT_ID = "pm_tc36_001";
  private static final String PAYMENT_TOKEN = "tok_tc36_synthetic";

  @Container
  static final MongoDBContainer MONGO = new MongoDBContainer(
      DockerImageName.parse("mongo:6.0.14"));

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri",
        () -> MONGO.getReplicaSetUrl("tc36_runtime"));
  }

  @LocalServerPort
  private int port;

  @Autowired private ReactiveMongoTemplate mongo;
  @Autowired private IngredientRepository ingredients;
  @Autowired private OrderRepository orders;
  @Autowired private UserRepository users;
  @Autowired private PaymentMethodRepository paymentMethods;
  @Autowired private IdempotencyRecordRepository idempotencyRecords;
  @Autowired private OutboxEventRepository outbox;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private ObjectMapper objectMapper;

  @MockBean(name="dataLoader")
  private CommandLineRunner dataLoader;

  private WebTestClient http;
  private User owner;

  @BeforeEach
  public void prepareRuntime() {
    http = WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(20))
        .build();

    Mono.when(
        mongo.remove(new Query(),IdempotencyRecord.class),
        mongo.remove(new Query(),InventoryReservation.class),
        mongo.remove(new Query(),OutboxEvent.class),
        mongo.remove(new Query(),TacoOrder.class),
        mongo.remove(new Query(),PaymentMethod.class),
        mongo.remove(new Query(),Ingredient.class),
        mongo.remove(new Query(),User.class)).block();

    owner = saveUser(OWNER,Role.USER);
    saveUser(OTHER,Role.USER);
    saveUser(ADMIN,Role.ADMIN);
    saveUser(KITCHEN,Role.KITCHEN);
  }

  @Test
  public void ingredientPutGetDeleteRunsAgainstRealHttpAndMongo() {
    ingredients.save(new Ingredient("TC36","Original",Type.WRAP)).block();
    CsrfSession csrf = csrfSession();

    http.put().uri("/api/v1/ingredients/TC36")
        .headers(basic(ADMIN))
        .header(csrf.headerName,csrf.token)
        .cookie(csrf.cookieName,csrf.cookieValue)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "id","TC36",
            "name","Runtime Tortilla",
            "type","WRAP",
            "dietaryTags",Collections.singletonList("VEGAN"),
            "allergens",Collections.emptyList(),
            "spiceLevel","NONE"))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.id").isEqualTo("TC36")
        .jsonPath("$.name").isEqualTo("Runtime Tortilla");

    http.get().uri("/api/v1/ingredients/TC36")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.name").isEqualTo("Runtime Tortilla");

    csrf = csrfSession();
    http.delete().uri("/api/v1/ingredients/TC36")
        .headers(basic(ADMIN))
        .header(csrf.headerName,csrf.token)
        .cookie(csrf.cookieName,csrf.cookieValue)
        .exchange()
        .expectStatus().isNoContent();

    http.get().uri("/api/v1/ingredients/TC36")
        .exchange()
        .expectStatus().isNotFound();
    assertEquals(0L,ingredients.count().block());
  }

  @Test
  public void orderPatchValidatesFieldsAndOwnershipAtRuntime() {
    TacoOrder order = sampleOrder(owner,"TC36-ORDER-EDIT");
    orders.save(order).block();
    CsrfSession csrf = csrfSession();

    http.patch().uri("/api/v1/orders/{id}",order.getId())
        .headers(basic(OWNER))
        .header(csrf.headerName,csrf.token)
        .cookie(csrf.cookieName,csrf.cookieValue)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("deliveryState","Jalisco","deliveryZip","44100"))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.deliveryState").isEqualTo("Jalisco")
        .jsonPath("$.deliveryZip").isEqualTo("44100")
        .jsonPath("$.ccNumber").doesNotExist()
        .jsonPath("$.ccCVV").doesNotExist();

    csrf = csrfSession();
    http.patch().uri("/api/v1/orders/{id}",order.getId())
        .headers(basic(OWNER))
        .header(csrf.headerName,csrf.token)
        .cookie(csrf.cookieName,csrf.cookieValue)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("status","DELIVERED"))
        .exchange()
        .expectStatus().isBadRequest();

    csrf = csrfSession();
    http.patch().uri("/api/v1/orders/{id}",order.getId())
        .headers(basic(OTHER))
        .header(csrf.headerName,csrf.token)
        .cookie(csrf.cookieName,csrf.cookieValue)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("deliveryZip","64000"))
        .exchange()
        .expectStatus().isNotFound();

    TacoOrder persisted = orders.findById(order.getId()).block();
    assertEquals("Jalisco",persisted.getDeliveryState());
    assertEquals("44100",persisted.getDeliveryZip());
  }

  @Test
  public void registrationPersistsHashAndNegativeSecurityIsEnforced() {
    CsrfSession csrf = csrfSession();
    MultiValueMap<String,String> form = new LinkedMultiValueMap<>();
    form.add("username","tc36-registered");
    form.add("password","registration-secret");
    form.add("fullname","TC36 Registered");
    form.add("street","Test 1");
    form.add("city","Guadalajara");
    form.add("state","Jalisco");
    form.add("zip","44100");
    form.add("phone","5550000036");
    form.add("email","tc36-registered@example.test");

    http.post().uri("/register")
        .header(csrf.headerName,csrf.token)
        .cookie(csrf.cookieName,csrf.cookieValue)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .bodyValue(form)
        .exchange()
        .expectStatus().is3xxRedirection();

    User registered = users.findByUsername("tc36-registered").block();
    assertNotNull(registered);
    assertNotEquals("registration-secret",registered.getPassword());
    assertTrue(passwordEncoder.matches(
        "registration-secret",registered.getPassword()));

    http.get().uri("/api/v1/users/me/orders")
        .exchange()
        .expectStatus().isUnauthorized();
    http.get().uri("/api/v1/admin/orders")
        .headers(basic(OWNER))
        .exchange()
        .expectStatus().isForbidden();
    http.get().uri("/api/v1/kitchen/orders")
        .headers(basic(OWNER))
        .exchange()
        .expectStatus().isForbidden();
  }

  @Test
  public void orderPostReplaysWithoutRepeatingSideEffects() {
    seedOrderDependencies();
    String key = "tc36-sequential-key";

    HttpOrderResult first = postOrder(key,"corr-tc36-sequential");
    HttpOrderResult replay = postOrder(key,"corr-tc36-sequential");

    assertEquals(HttpStatus.CREATED,first.status,first.body);
    assertEquals(HttpStatus.OK,replay.status);
    assertEquals(first.orderId,replay.orderId);
    assertSafeOrderBody(first.body);
    assertOrderSideEffects(1L,1L,1L,1L,19);
  }

  @Test
  public void concurrentOrderPostProducesOneEffectAndStableReplay() {
    seedOrderDependencies();
    String key = "tc36-concurrent-key";
    Sinks.One<Void> start = Sinks.one();

    Mono<HttpOrderResult> requestA = start.asMono()
        .then(Mono.fromCallable(() -> postOrder(
            key,"corr-tc36-concurrent")))
        .subscribeOn(Schedulers.boundedElastic());
    Mono<HttpOrderResult> requestB = start.asMono()
        .then(Mono.fromCallable(() -> postOrder(
            key,"corr-tc36-concurrent")))
        .subscribeOn(Schedulers.boundedElastic());

    Mono<java.util.List<HttpOrderResult>> concurrent = Flux.merge(
        requestA,requestB).collectList()
        .doOnSubscribe(ignored -> start.tryEmitEmpty());

    java.util.List<HttpOrderResult> results = concurrent.block(
        Duration.ofSeconds(30));
    assertNotNull(results);
    assertEquals(2,results.size());
    assertEquals(1,results.stream()
        .filter(result -> result.status == HttpStatus.CREATED).count());
    assertTrue(results.stream().allMatch(result ->
        result.status == HttpStatus.CREATED
            || result.status == HttpStatus.OK
            || result.status == HttpStatus.CONFLICT));

    HttpOrderResult replay = postOrder(
        key,"corr-tc36-concurrent");
    assertEquals(HttpStatus.OK,replay.status);
    assertNotNull(replay.orderId);
    assertOrderSideEffects(1L,1L,1L,1L,19);
  }

  private User saveUser(String username,Role role) {
    User user = new User(
        username,passwordEncoder.encode(PASSWORD),"TC36 " + role,
        "Test 1","Guadalajara","Jalisco","44100","5550000036",
        username + "@example.test");
    user.setRole(role);
    return users.save(user).block();
  }

  private TacoOrder sampleOrder(User user,String id) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setUser(user);
    order.setDeliveryName("Original Name");
    order.setDeliveryStreet("Original Street");
    order.setDeliveryCity("Monterrey");
    order.setDeliveryState("Nuevo Leon");
    order.setDeliveryZip("64000");
    return order;
  }

  private void seedOrderDependencies() {
    Ingredient ingredient = new Ingredient(
        "FLTO","Flour Tortilla",Type.WRAP,
        new BigDecimal("10.00"),true,20,2);
    ingredients.save(ingredient).block();
    Ingredient filling = new Ingredient(
        "TC36F","TC36 Filling",Type.PROTEIN,
        new BigDecimal("5.00"),true,20,2);
    ingredients.save(filling).block();

    PaymentMethod payment = new PaymentMethod(owner);
    payment.setId(PAYMENT_ID);
    payment.setPaymentToken(PAYMENT_TOKEN);
    payment.setBrand("VISA");
    payment.setLast4("4242");
    payment.setExpiration("12/30");
    paymentMethods.save(payment).block();
  }

  private HttpOrderResult postOrder(
      String idempotencyKey,String correlationId) {
    CsrfSession csrf = csrfSession();
    EntityExchangeResult<byte[]> response = http.post()
        .uri("/api/v1/orders")
        .headers(basic(OWNER))
        .header(csrf.headerName,csrf.token)
        .header("Idempotency-Key",idempotencyKey)
        .header("X-Correlation-Id",correlationId)
        .cookie(csrf.cookieName,csrf.cookieValue)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(orderRequest())
        .exchange()
        .expectBody()
        .returnResult();

    HttpStatus status = response.getStatus();
    String body = new String(response.getResponseBody() != null
        ? response.getResponseBody() : new byte[0],StandardCharsets.UTF_8);
    String orderId = null;
    if (!body.isEmpty() && status != HttpStatus.CONFLICT) {
      try {
        orderId = objectMapper.readTree(body).path("id").asText(null);
      } catch (Exception error) {
        throw new AssertionError("Order response is not valid JSON: " + body,error);
      }
    }
    return new HttpOrderResult(status,orderId,body);
  }

  private Map<String,Object> orderRequest() {
    Map<String,Object> taco = Map.of(
        "name","TC36 Runtime Taco",
        "ingredientIds",Arrays.asList("FLTO","TC36F"));
    Map<String,Object> item = Map.of("taco",taco,"quantity",1);
    return Map.of(
        "deliveryName","TC36 Owner",
        "deliveryStreet","Test 1",
        "deliveryCity","Guadalajara",
        "deliveryState","Jalisco",
        "deliveryZip","44100",
        "paymentMethodId",PAYMENT_ID,
        "items",Collections.singletonList(item));
  }

  private void assertOrderSideEffects(long orderCount,long recordCount,
      long reservationCount,long outboxCount,int remainingStock) {
    assertEquals(orderCount,orders.count().block());
    assertEquals(recordCount,idempotencyRecords.count().block());
    assertEquals(reservationCount,
        mongo.count(new Query(),InventoryReservation.class).block());
    assertEquals(outboxCount,outbox.count().block());
    assertEquals(remainingStock,
        ingredients.findById("FLTO").block().getStockOnHand());
  }

  private void assertSafeOrderBody(String body) {
    assertFalse(body.contains(PAYMENT_TOKEN));
    for (String forbidden : Arrays.asList(
        "ccNumber","ccCVV","cvv","paymentToken","password")) {
      assertFalse(body.contains(forbidden),
          () -> "Sensitive field leaked in order response: " + forbidden);
    }
  }

  private Consumer<HttpHeaders> basic(String username) {
    return headers -> headers.setBasicAuth(username,PASSWORD);
  }

  private CsrfSession csrfSession() {
    EntityExchangeResult<byte[]> result = http.get().uri("/login")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().exists("X-CSRF-TOKEN")
        .expectBody()
        .returnResult();

    String token = result.getResponseHeaders().getFirst("X-CSRF-TOKEN");
    HttpCookie cookie = result.getResponseCookies().getFirst("JSESSIONID");
    assertNotNull(token);
    assertNotNull(cookie);
    return new CsrfSession("X-CSRF-TOKEN",token,
        cookie.getName(),cookie.getValue());
  }

  private static final class HttpOrderResult {
    private final HttpStatus status;
    private final String orderId;
    private final String body;

    private HttpOrderResult(HttpStatus status,String orderId,String body) {
      this.status = status;
      this.orderId = orderId;
      this.body = body;
    }
  }

  private static final class CsrfSession {
    private final String headerName;
    private final String token;
    private final String cookieName;
    private final String cookieValue;

    private CsrfSession(String headerName,String token,
        String cookieName,String cookieValue) {
      this.headerName = headerName;
      this.token = token;
      this.cookieName = cookieName;
      this.cookieValue = cookieValue;
    }
  }

  @TestConfiguration
  static class HttpTestConfiguration {

    @Bean
    CsrfTokenController csrfTokenController() {
      return new CsrfTokenController();
    }
  }

  @RestController
  static class CsrfTokenController {

    @GetMapping("/login")
    public String csrf(HttpServletRequest request,
        javax.servlet.http.HttpServletResponse response) {
      CsrfToken token = (CsrfToken) request.getAttribute(
          CsrfToken.class.getName());
      response.setHeader("X-CSRF-TOKEN",token.getToken());
      return "login";
    }
  }
}
