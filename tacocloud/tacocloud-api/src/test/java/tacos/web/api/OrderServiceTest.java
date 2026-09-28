package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.security.core.Authentication;
import org.springframework.data.domain.Pageable;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.PaymentMethod;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;
import tacos.web.api.dto.ApiDtos.OrderCreateRequest;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.mapper.ApiMapper;
import tacos.web.api.mapper.ApiMapper.OrderCreateCommand;
import tacos.web.api.mapper.ApiMapper.OrderItemCommand;
import tacos.web.api.mapper.ApiMapper.TacoCommand;

public class OrderServiceTest {

  private OrderRepository repo;
  private OrderMessagingService orderMessages;
  private EmailOrderService emailOrderService;

  private OrderService service;
  private UserRepository userRepo;
  private PaymentMethodRepository paymentMethodRepo;
  private IngredientRepository ingredientRepo;

  @BeforeEach
  public void setup() {

    repo = Mockito.mock(OrderRepository.class);

    orderMessages = Mockito.mock(OrderMessagingService.class);

    emailOrderService = Mockito.mock(EmailOrderService.class);
    
    userRepo = Mockito.mock(UserRepository.class);
    
    paymentMethodRepo = Mockito.mock(PaymentMethodRepository.class);
    ingredientRepo = Mockito.mock(IngredientRepository.class);

    service = new OrderService(
        repo,orderMessages,emailOrderService,userRepo,paymentMethodRepo,ingredientRepo);
  }


  /*
   * TC-07
   * Una conversión produce exactamente
   * un guardado y una publicación.
   */
  @Test
  public void shouldSaveAndPublishExactlyOnce() {

    Mono<EmailOrder> emailOrder = 
        Mono.just(Mockito.mock(EmailOrder.class));

    TacoOrder convertedOrder = new TacoOrder();

    TacoOrder savedOrder = new TacoOrder();

    when(emailOrderService.convertEmailOrderToDomainOrder(emailOrder))
        .thenReturn(Mono.just(convertedOrder));

    when(repo.save(convertedOrder)).thenReturn(Mono.just(savedOrder));

    StepVerifier.create(service.createOrderFromEmail(emailOrder))
        .assertNext(order -> assertSame(savedOrder,order))
        .verifyComplete();

    verify(emailOrderService,times(1))
      .convertEmailOrderToDomainOrder(emailOrder);

    verify(repo,times(1)).save(convertedOrder);

    verify(orderMessages,times(1)).sendOrder(savedOrder);
  }


  /*
   * TC-07
   * Error de conversión o guardado no debe provocar publicación.
   */
  @Test
  public void shouldNotPublishWhenConversionOrSaveFails() {

    Mono<EmailOrder> emailOrder = 
      Mono.just(Mockito.mock(EmailOrder.class));


    // Falla la conversión.
    when(emailOrderService.convertEmailOrderToDomainOrder(emailOrder))
      .thenReturn(Mono.error(new RuntimeException("Conversion error")));

    StepVerifier.create(service.createOrderFromEmail(emailOrder))
        .expectError(RuntimeException.class)
        .verify();

    verify(repo,never()).save(any(TacoOrder.class));

    verify(orderMessages,never()).sendOrder(any(TacoOrder.class));


    // La conversión funciona pero falla el guardado.
    reset(repo,orderMessages,emailOrderService);

    TacoOrder convertedOrder = new TacoOrder();

    when(emailOrderService.convertEmailOrderToDomainOrder(emailOrder))
        .thenReturn(Mono.just(convertedOrder));

    when(repo.save(convertedOrder))
      .thenReturn(Mono.error(new RuntimeException("Save error")));

    StepVerifier.create(service.createOrderFromEmail(emailOrder))
        .expectError(RuntimeException.class)
        .verify();

    verify(repo,times(1)).save(convertedOrder);

    verify(orderMessages,never()).sendOrder(any(TacoOrder.class));
  }


  /*
   * TC-07
   * Publisher frío:
   * la conversión no debe
   * ejecutarse dos veces.
   */
  @Test
  public void shouldNotDuplicateColdPublisherExecution() {

    Mono<EmailOrder> emailOrder =
        Mono.just(Mockito.mock(EmailOrder.class));

    TacoOrder convertedOrder = new TacoOrder();

    TacoOrder savedOrder = new TacoOrder();

    AtomicInteger conversionSubscriptions = new AtomicInteger();

    AtomicInteger saveSubscriptions = new AtomicInteger();

    Mono<TacoOrder> coldConversion =
        Mono.defer(() -> {
          conversionSubscriptions.incrementAndGet();
          return Mono.just(convertedOrder);
        });

    when(emailOrderService.convertEmailOrderToDomainOrder(emailOrder))
        .thenReturn(coldConversion);

    when(repo.save(convertedOrder))
        .thenAnswer(invocation ->
            Mono.defer(() -> {
              saveSubscriptions.incrementAndGet();
              return Mono.just(savedOrder);
            }));

    StepVerifier.create(service.createOrderFromEmail(emailOrder))
        .expectNext(savedOrder)
        .verifyComplete();

    assertEquals(1,conversionSubscriptions.get());

    assertEquals(1,saveSubscriptions.get());

    verify(orderMessages,times(1)).sendOrder(savedOrder);
  }

  @Test
  public void shouldEnforceOrderOwnershipInService() {

    User jose = Mockito.mock(User.class);

    User ana = Mockito.mock(User.class);

    when(jose.getUsername()).thenReturn("jose");

    when(ana.getUsername()).thenReturn("ana");


    TacoOrder joseOrder = new TacoOrder();

    joseOrder.setUser(jose);

    TacoOrder anaOrder = new TacoOrder();

    anaOrder.setUser(ana);


    Authentication userAuth = Mockito.mock(Authentication.class);

    when(userAuth.getName())
        .thenReturn("jose");

    doReturn(
      Collections.singletonList(
          new SimpleGrantedAuthority(
              "ROLE_USER")))
      .when(userAuth)
      .getAuthorities();

    when(
        userRepo.findByUsername("jose"))
        .thenReturn(Mono.just(jose));


    when(
        repo.findByUserOrderByPlacedAtDesc(
            Mockito.eq(jose),
            Mockito.any(Pageable.class)))

        .thenReturn(Flux.just(joseOrder));

    StepVerifier.create(
        service.findOrdersFor(userAuth))
        .expectNext(joseOrder)
        .verifyComplete();

    assertFalse(
        service.canAccessOrder(anaOrder,userAuth));

    Authentication adminAuth = Mockito.mock(Authentication.class);

    doReturn(
      Collections.singletonList(
          new SimpleGrantedAuthority(
              "ROLE_ADMIN")))
      .when(adminAuth)
      .getAuthorities();

    when(repo.findAll())
        .thenReturn(Flux.just(joseOrder,anaOrder));

    StepVerifier.create(service.findOrdersFor(adminAuth))
        .expectNext(joseOrder,anaOrder)
        .verifyComplete();

    assertTrue(service.canAccessOrder(anaOrder,adminAuth));
  }

  @Test
  public void shouldCreateOrderWithoutPaymentData()
      throws Exception {

    User user = Mockito.mock(User.class);

    when(user.getId()).thenReturn("USER-1");

    Authentication authentication = Mockito.mock(Authentication.class);

    when(authentication.getName()).thenReturn("jose");

    when(userRepo.findByUsername("jose")).thenReturn(Mono.just(user));

    PaymentMethod paymentMethod = new PaymentMethod(user);

    paymentMethod.setId("PAYMENT-1");
    paymentMethod.setPaymentToken("tok_fake_test");
    paymentMethod.setBrand("VISA");
    paymentMethod.setLast4("1111");

    when(paymentMethodRepo.findById("PAYMENT-1")).thenReturn(Mono.just(paymentMethod));

    OrderCreateCommand command = Mockito.mock(OrderCreateCommand.class);

    when(command.getPaymentMethodId()).thenReturn("PAYMENT-1");
    when(command.getItems()).thenReturn(Collections.singletonList(
        orderItem("Synthetic Taco",1,"FLTO")));

    Ingredient flour = catalogIngredient("FLTO","0.75");
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(flour));

    TacoOrder saved = new TacoOrder();

    saved.setId("ORDER-1");

    when(repo.save(any(TacoOrder.class))).thenReturn(Mono.just(saved));

    StepVerifier.create(
      service.createOrder(command,authentication))
      .expectNext(saved)
      .verifyComplete();

    ArgumentCaptor<TacoOrder> captor =
        ArgumentCaptor.forClass(TacoOrder.class);

    verify(repo).save(captor.capture());

    TacoOrder persisted = captor.getValue();

    List<String> fieldNames = Arrays.stream(TacoOrder.class.getDeclaredFields())
        .map(Field::getName)
        .collect(Collectors.toList());

    assertFalse(fieldNames.contains("ccNumber"));
    assertFalse(fieldNames.contains("ccCVV"));
    assertFalse(fieldNames.contains("ccExpiration"));

    ArgumentCaptor<TacoOrder>
        messageCaptor = ArgumentCaptor.forClass(TacoOrder.class);

    verify(orderMessages).sendOrder(messageCaptor.capture());

    ObjectMapper mapper = new ObjectMapper();
  
    String eventJson =
        mapper.writeValueAsString(messageCaptor.getValue());

    assertFalse(eventJson.contains("paymentToken"));
    assertFalse(eventJson.contains("cardNumber"));
    assertFalse(eventJson.contains("cvv"));
    assertFalse(eventJson.contains("last4"));
    assertFalse(eventJson.contains("brand"));
  }

  @Test
  public void shouldRejectPaymentMethodOwnedByAnotherUser() {

    User authenticatedUser = Mockito.mock(User.class);
    when(authenticatedUser.getId()).thenReturn("USER-1");

    User otherUser = Mockito.mock(User.class);
    when(otherUser.getId()).thenReturn("USER-2");

    Authentication authentication = Mockito.mock(Authentication.class);
    when(authentication.getName()).thenReturn("jose");
    when(userRepo.findByUsername("jose"))
        .thenReturn(Mono.just(authenticatedUser));

    PaymentMethod foreignMethod = new PaymentMethod(otherUser);
    foreignMethod.setId("PAYMENT-1");
    foreignMethod.setPaymentToken("tok_fake_other_user");

    when(paymentMethodRepo.findById("PAYMENT-1"))
        .thenReturn(Mono.just(foreignMethod));

    OrderCreateCommand command = Mockito.mock(OrderCreateCommand.class);
    when(command.getPaymentMethodId()).thenReturn("PAYMENT-1");

    StepVerifier.create(service.createOrder(command,authentication))
        .expectErrorMatches(error ->
            error instanceof org.springframework.web.server.ResponseStatusException
              && ((org.springframework.web.server.ResponseStatusException) error)
                  .getStatus() == org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
        .verify();

    verify(repo,never()).save(any(TacoOrder.class));
    verify(orderMessages,never()).sendOrder(any(TacoOrder.class));
  }

  @Test
  public void shouldCalculateDecimalPricesAndQuantityOnServer()
      throws Exception {

    Authentication authentication = authenticatedUserWithPayment();

    when(ingredientRepo.findById("FLTO"))
        .thenReturn(Mono.just(catalogIngredient("FLTO","0.10")));
    when(ingredientRepo.findById("CHED"))
        .thenReturn(Mono.just(catalogIngredient("CHED","0.235")));
    when(repo.save(any(TacoOrder.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

    String manipulatedJson = "{"
        + "\"paymentMethodId\":\"PAYMENT-1\","
        + "\"total\":\"0.01\","
        + "\"items\":[{"
        + "\"quantity\":2,"
        + "\"unitPriceAtPurchase\":\"0.01\","
        + "\"subtotal\":\"0.02\","
        + "\"taco\":{\"name\":\"Decimal Taco\","
        + "\"ingredientIds\":[\"FLTO\",\"CHED\"]}}]}";

    OrderCreateRequest request = new ObjectMapper()
        .readValue(manipulatedJson,OrderCreateRequest.class);
    OrderCreateCommand command = ApiMapper.toCommand(request);

    StepVerifier.create(service.createOrder(command,authentication))
        .assertNext(order -> {
          assertEquals("MXN",order.getCurrency());
          assertEquals(new BigDecimal("0.68"),order.getTotal());
          assertEquals(1,order.getItems().size());
          assertEquals(2,order.getItems().get(0).getQuantity());
          assertEquals(
              new BigDecimal("0.34"),
              order.getItems().get(0).getUnitPriceAtPurchase());
          assertEquals(
              new BigDecimal("0.68"),
              order.getItems().get(0).getSubtotal());

          assertEquals(
              new BigDecimal("0.68"),
              ApiMapper.toResponse(order).getTotal());
          assertEquals(
              new BigDecimal("0.34"),
              ApiMapper.toResponse(order)
                  .getItems().get(0).getUnitPriceAtPurchase());
        })
        .verifyComplete();
  }

  @Test
  public void shouldKeepHistoricalPriceSnapshotAfterCatalogChange() {

    Authentication authentication = authenticatedUserWithPayment();
    Ingredient flour = catalogIngredient("FLTO","1.25");

    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(flour));
    when(repo.save(any(TacoOrder.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

    OrderCreateCommand command = orderCommand(2,"FLTO");

    StepVerifier.create(service.createOrder(command,authentication))
        .assertNext(order -> {
          BigDecimal historicalPrice = order.getItems().get(0)
              .getUnitPriceAtPurchase();

          flour.setUnitPrice(new BigDecimal("9.99"));

          assertEquals(new BigDecimal("1.25"),historicalPrice);
          assertEquals(
              new BigDecimal("1.25"),
              order.getItems().get(0).getUnitPriceAtPurchase());
          assertEquals(
              new BigDecimal("2.50"),
              order.getItems().get(0).getSubtotal());
        })
        .verifyComplete();
  }

  @Test
  public void shouldRejectZeroNegativeAndExcessiveQuantities() {

    Authentication authentication = authenticatedUserWithPayment();
    service.configureMaxQuantity(2);

    for (int invalidQuantity : Arrays.asList(0,-1,3)) {
      StepVerifier.create(
          service.createOrder(orderCommand(invalidQuantity,"FLTO"),authentication))
          .expectErrorSatisfies(error -> {
            assertTrue(error instanceof ApiException);
            assertEquals(
                422,
                ((ApiException) error).getStatus().value());
          })
          .verify();
    }

    verify(ingredientRepo,never()).findById(any(String.class));
    verify(repo,never()).save(any(TacoOrder.class));
  }

  private Authentication authenticatedUserWithPayment() {

    User user = Mockito.mock(User.class);
    when(user.getId()).thenReturn("USER-1");

    Authentication authentication = Mockito.mock(Authentication.class);
    when(authentication.getName()).thenReturn("jose");

    when(userRepo.findByUsername("jose")).thenReturn(Mono.just(user));

    PaymentMethod paymentMethod = new PaymentMethod(user);
    paymentMethod.setId("PAYMENT-1");
    paymentMethod.setPaymentToken("tok_fake_tc14");

    when(paymentMethodRepo.findById("PAYMENT-1"))
        .thenReturn(Mono.just(paymentMethod));

    return authentication;
  }

  private Ingredient catalogIngredient(String id,String price) {
    return new Ingredient(
        id,"Synthetic Ingredient",Ingredient.Type.VEGGIES,
        new BigDecimal(price),true,100,10);
  }

  private OrderCreateCommand orderCommand(int quantity,String... ingredientIds) {
    return new OrderCreateCommand(
        "Jose","Street","City","AG","20000","PAYMENT-1",
        Collections.singletonList(
            orderItem("Synthetic Taco",quantity,ingredientIds)));
  }

  private OrderItemCommand orderItem(
      String name,int quantity,String... ingredientIds) {
    return new OrderItemCommand(
        new TacoCommand(name,Arrays.asList(ingredientIds)),quantity);
  }
}
