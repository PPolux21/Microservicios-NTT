package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.InventoryReservation;
import tacos.InventoryReservation.Status;
import tacos.PaymentMethod;
import tacos.ReorderAttempt;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.TacoOrder.OrderItem;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.ReorderAttemptRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;
import tacos.messaging.OrderEvent;
import tacos.web.api.OrderService.ReorderStatus;
import tacos.web.api.TacoDesignValidator.ValidationResult;
import tacos.web.api.TacoDesignValidator.RuleViolation;
import tacos.web.api.coupon.CouponProperties;
import tacos.web.api.coupon.CouponProperties.CouponRule;
import tacos.web.api.coupon.CouponProperties.CouponType;
import tacos.web.api.coupon.CouponService;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

public class OrderReorderServiceTest {

  private OrderRepository orders;
  private ReorderAttemptRepository attempts;
  private PaymentMethodRepository payments;
  private IngredientRepository ingredients;
  private InventoryService inventory;
  private OrderMessagingService messages;
  private TacoDesignValidator validator;
  private CouponProperties couponProperties;
  private OrderService service;
  private User alice;
  private TacoOrder original;
  private Map<String,TacoOrder> storedOrders;
  private Map<String,ReorderAttempt> storedAttempts;

  @BeforeEach
  public void setup() {
    orders = Mockito.mock(OrderRepository.class);
    attempts = Mockito.mock(ReorderAttemptRepository.class);
    payments = Mockito.mock(PaymentMethodRepository.class);
    ingredients = Mockito.mock(IngredientRepository.class);
    inventory = Mockito.mock(InventoryService.class);
    messages = Mockito.mock(OrderMessagingService.class);
    validator = Mockito.mock(TacoDesignValidator.class);
    UserRepository users = Mockito.mock(UserRepository.class);
    storedOrders = new ConcurrentHashMap<>();
    storedAttempts = new ConcurrentHashMap<>();

    alice = user("U-A","alice");
    original = historicalOrder(alice);
    storedOrders.put(original.getId(),original);

    when(users.findByUsername("alice")).thenReturn(Mono.just(alice));
    when(orders.findByIdAndUserId(any(String.class),any(String.class)))
        .thenAnswer(invocation -> {
          TacoOrder found = storedOrders.get(invocation.getArgument(0));
          return found != null
              && invocation.getArgument(1).equals(found.getUserId())
              ? Mono.just(found) : Mono.empty();
        });
    when(orders.save(any(TacoOrder.class))).thenAnswer(invocation -> {
      TacoOrder order = invocation.getArgument(0);
      storedOrders.put(order.getId(),order);
      return Mono.just(order);
    });

    when(attempts.findById(any(String.class))).thenAnswer(invocation ->
        Mono.justOrEmpty(storedAttempts.get(invocation.getArgument(0))));
    when(attempts.insert(any(ReorderAttempt.class))).thenAnswer(invocation -> {
      ReorderAttempt attempt = invocation.getArgument(0);
      if (storedAttempts.putIfAbsent(attempt.getId(),attempt) != null) {
        return Mono.error(new DuplicateKeyException("duplicate"));
      }
      return Mono.just(attempt);
    });
    when(attempts.save(any(ReorderAttempt.class))).thenAnswer(invocation -> {
      ReorderAttempt attempt = invocation.getArgument(0);
      storedAttempts.put(attempt.getId(),attempt);
      return Mono.just(attempt);
    });
    when(attempts.deleteById(any(String.class))).thenAnswer(invocation -> {
      storedAttempts.remove(invocation.getArgument(0));
      return Mono.empty();
    });

    Ingredient base = ingredient("BASE",Type.WRAP,"4.00",true,20);
    Ingredient filling = ingredient("FILL",Type.PROTEIN,"11.00",true,20);
    when(ingredients.findById("BASE")).thenReturn(Mono.just(base));
    when(ingredients.findById("FILL")).thenReturn(Mono.just(filling));

    ValidationResult valid = Mockito.mock(ValidationResult.class);
    when(valid.isValid()).thenReturn(true);
    when(validator.validateResolved(any(String.class),any(),any()))
        .thenReturn(valid);

    PaymentMethod payment = payment(alice,"PAY-A");
    when(payments.findById("PAY-A")).thenReturn(Mono.just(payment));
    when(inventory.reserve(any(TacoOrder.class))).thenAnswer(invocation -> {
      TacoOrder order = invocation.getArgument(0);
      return Mono.just(new InventoryReservation(
          order.getId(),order.getId(),Status.RESERVED,Collections.emptyList()));
    });

    couponProperties = new CouponProperties();
    CouponService coupons = new CouponService(
        couponProperties,
        Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"),ZoneOffset.UTC));
    service = new OrderService(
        orders,messages,Mockito.mock(EmailOrderService.class),users,payments,
        ingredients,coupons,inventory,
        new TacoClassificationService(ingredients),validator,attempts);
  }

  @Test
  public void shouldQuoteCurrentPriceWithoutSideEffects() {
    StepVerifier.create(service.reorder(
        "ORDER-OLD","PAY-A",false,"KEY-1",authentication("alice")))
        .assertNext(result -> {
          assertEquals(ReorderStatus.REORDER_QUOTE,result.getStatus());
          assertTrue(result.isRequiresConfirmation());
          assertEquals(new BigDecimal("10.00"),result.getOriginalTotal());
          assertEquals(new BigDecimal("15.00"),result.getCurrentTotal());
          assertEquals(new BigDecimal("5.00"),result.getDifference());
          assertTrue(result.getDifferences().contains("PRICE_CHANGED"));
        })
        .verifyComplete();

    assertEquals(1,storedOrders.size());
    verify(inventory,never()).reserve(any(TacoOrder.class));
    verify(attempts,never()).insert(any(ReorderAttempt.class));
    verify(messages,never()).sendOrder(any(OrderEvent.class));
  }

  @Test
  public void shouldCreateNewOrderAndReturnItForSameIdempotencyKey() {
    Date originalPlacedAt = original.getPlacedAt();
    BigDecimal originalPrice = original.getItems().get(0).getUnitPriceAtPurchase();

    TacoOrder first = service.reorder(
        "ORDER-OLD","PAY-A",true,"KEY-CREATE",authentication("alice"))
        .map(result -> result.getOrder()).block();
    TacoOrder retry = service.reorder(
        "ORDER-OLD","PAY-A",true,"KEY-CREATE",authentication("alice"))
        .map(result -> result.getOrder()).block();

    assertSame(first,retry);
    assertNotEquals(original.getId(),first.getId());
    assertNotEquals(originalPlacedAt,first.getPlacedAt());
    assertEquals(TacoOrder.Status.CREATED,first.getStatus());
    assertEquals(new BigDecimal("15.00"),
        first.getItems().get(0).getUnitPriceAtPurchase());
    assertEquals(2,storedOrders.size());
    TacoOrder reloadedOriginal = storedOrders.get("ORDER-OLD");
    assertEquals(originalPlacedAt,reloadedOriginal.getPlacedAt());
    assertEquals(TacoOrder.Status.CREATED,reloadedOriginal.getStatus());
    assertEquals(originalPrice,
        reloadedOriginal.getItems().get(0).getUnitPriceAtPurchase());
    assertEquals(new BigDecimal("10.00"),reloadedOriginal.getTotal());
    verify(inventory,times(1)).reserve(any(TacoOrder.class));
    verify(orders,times(1)).save(any(TacoOrder.class));
    verify(messages,times(1)).sendOrder(any(OrderEvent.class));
  }

  @Test
  public void shouldRejectCurrentStockFailureWithoutCreatingOrder() {
    when(inventory.reserve(any(TacoOrder.class))).thenReturn(Mono.error(
        ApiException.conflict("INSUFFICIENT_STOCK","No stock.")));

    StepVerifier.create(service.reorder(
        "ORDER-OLD","PAY-A",true,"KEY-STOCK",authentication("alice")))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof ApiException);
          assertEquals("INSUFFICIENT_STOCK",((ApiException) error).getCode());
        })
        .verify();

    assertEquals(1,storedOrders.size());
    assertTrue(storedAttempts.isEmpty());
    verify(orders,never()).save(any(TacoOrder.class));
  }

  @Test
  public void shouldRejectCurrentUnavailableIngredientOrDesignRuleBeforeInventory() {
    ValidationResult invalid = new ValidationResult(
        null,Collections.singletonList(new RuleViolation(
            "INGREDIENT_UNAVAILABLE","Ingredient is unavailable.")));
    when(validator.validateResolved(any(String.class),any(),any()))
        .thenReturn(invalid);
    when(validator.invalidDesign(invalid)).thenReturn(
        ApiException.unprocessable("TACO_DESIGN_INVALID","Invalid design."));

    StepVerifier.create(service.reorder(
        "ORDER-OLD","PAY-A",true,"KEY-RULE",authentication("alice")))
        .expectErrorSatisfies(error -> assertEquals(
            "TACO_DESIGN_INVALID",((ApiException) error).getCode()))
        .verify();

    verify(inventory,never()).reserve(any(TacoOrder.class));
    verify(orders,never()).save(any(TacoOrder.class));
  }

  @Test
  public void shouldDropExpiredHistoricalCouponAndRequestConfirmation() {
    CouponRule expired = new CouponRule();
    expired.setType(CouponType.FIXED);
    expired.setValue(new BigDecimal("5.00"));
    expired.setValidUntil(LocalDate.of(2025,1,1));
    couponProperties.getRules().put("OLD5",expired);
    original.setAppliedCouponCode("OLD5");

    StepVerifier.create(service.reorder(
        "ORDER-OLD","PAY-A",false,"KEY-COUPON",authentication("alice")))
        .assertNext(result -> {
          assertEquals(ReorderStatus.REORDER_QUOTE,result.getStatus());
          assertEquals(new BigDecimal("15.00"),result.getCurrentTotal());
          assertTrue(result.getDifferences().contains("COUPON_NOT_APPLICABLE"));
        })
        .verifyComplete();

    verify(orders,never()).save(any(TacoOrder.class));
  }

  @Test
  public void shouldRejectForeignPaymentAndForeignOrder() {
    User bob = user("U-B","bob");
    when(payments.findById("PAY-B")).thenReturn(Mono.just(payment(bob,"PAY-B")));

    StepVerifier.create(service.reorder(
        "ORDER-OLD","PAY-B",true,"KEY-PAY",authentication("alice")))
        .expectErrorMatches(error ->
            error instanceof org.springframework.web.server.ResponseStatusException)
        .verify();
    verify(inventory,never()).reserve(any(TacoOrder.class));

    UserRepository foreignUsers = Mockito.mock(UserRepository.class);
    when(foreignUsers.findByUsername("bob")).thenReturn(Mono.just(bob));
    CouponService coupons = new CouponService(couponProperties,Clock.systemUTC());
    OrderService foreignService = new OrderService(
        orders,messages,Mockito.mock(EmailOrderService.class),foreignUsers,
        payments,ingredients,coupons,inventory,
        new TacoClassificationService(ingredients),validator,attempts);

    StepVerifier.create(foreignService.reorder(
        "ORDER-OLD","PAY-B",true,"KEY-OWNER",authentication("bob")))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof ApiException);
          assertEquals(404,((ApiException) error).getStatus().value());
        })
        .verify();
  }

  private TacoOrder historicalOrder(User owner) {
    Ingredient oldBase = ingredient("BASE",Type.WRAP,"2.00",true,1);
    Ingredient oldFilling = ingredient("FILL",Type.PROTEIN,"8.00",true,1);
    Taco taco = new Taco();
    taco.setName("Historical Taco");
    taco.setIngredients(Arrays.asList(oldBase,oldFilling));
    TacoOrder order = new TacoOrder();
    order.setId("ORDER-OLD");
    order.setUser(owner);
    order.setPlacedAt(Date.from(Instant.parse("2025-01-01T12:00:00Z")));
    order.setDeliveryName("Alice");
    order.setDeliveryStreet("Old Street 1");
    order.setDeliveryCity("Aguascalientes");
    order.setDeliveryState("AGS");
    order.setDeliveryZip("20000");
    order.addItem(new OrderItem(
        taco,1,new BigDecimal("10.00"),new BigDecimal("10.00")));
    order.setSubtotal(new BigDecimal("10.00"));
    order.setTotal(new BigDecimal("10.00"));
    return order;
  }

  private Ingredient ingredient(String id,Type type,String price,
      boolean available,int stock) {
    return new Ingredient(
        id,id,type,new BigDecimal(price),available,stock,1);
  }

  private User user(String id,String username) {
    User user = new User(username,"{noop}secret",username,"street","city",
        "state","00000","000",username + "@example.test");
    user.setId(id);
    return user;
  }

  private PaymentMethod payment(User owner,String id) {
    PaymentMethod method = new PaymentMethod(owner);
    method.setId(id);
    method.setPaymentToken("tok_synthetic_" + id);
    return method;
  }

  private Authentication authentication(String username) {
    return new UsernamePasswordAuthenticationToken(
        username,"password",AuthorityUtils.createAuthorityList("ROLE_USER"));
  }
}
