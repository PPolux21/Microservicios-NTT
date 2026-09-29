package tacos.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.User;
import tacos.TacoOrder;
import tacos.TacoOrder.OrderItem;
import tacos.web.api.mapper.ApiMapper;
import tacos.web.api.mapper.ApiMapper.OrderCreateCommand;
import tacos.web.api.mapper.ApiMapper.OrderItemCommand;
import tacos.web.api.mapper.ApiMapper.OrderQuote;
import tacos.web.api.mapper.ApiMapper.OrderQuoteCommand;
import tacos.web.api.coupon.CouponService;
import tacos.web.api.coupon.CouponService.CouponApplication;
import tacos.web.api.TacoClassificationService.ClassifiedTaco;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;

@Service
public class OrderService {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
  private static final String ORDER_CURRENCY = "MXN";

  private OrderRepository repo;
  private OrderMessagingService orderMessages;
  private EmailOrderService emailOrderService;
  private UserRepository userRepo;
  private PaymentMethodRepository paymentMethodRepo;
  private IngredientRepository ingredientRepo;
  private CouponService couponService;
  private InventoryService inventoryService;
  private TacoClassificationService classificationService;
  private TacoDesignValidator designValidator;
  private int maxQuantity = 10;

  public OrderService(
      OrderRepository repo,
      OrderMessagingService orderMessages,
      EmailOrderService emailOrderService,
      UserRepository userRepo,
      PaymentMethodRepository paymentMethodRepo,
      IngredientRepository ingredientRepo,
      CouponService couponService,
      InventoryService inventoryService,
      TacoClassificationService classificationService,
      TacoDesignValidator designValidator) {

    this.repo = repo;
    this.orderMessages = orderMessages;
    this.emailOrderService = emailOrderService;
    this.userRepo = userRepo;
    this.paymentMethodRepo = paymentMethodRepo;
    this.ingredientRepo = ingredientRepo;
    this.couponService = couponService;
    this.inventoryService = inventoryService;
    this.classificationService = classificationService;
    this.designValidator = designValidator;
  }

  @Value("${tacocloud.orders.max-quantity:10}")
  void configureMaxQuantity(int maxQuantity) {
    this.maxQuantity = maxQuantity;
  }

  public Mono<TacoOrder> createOrder(OrderCreateCommand command, 
      Authentication authentication) {

    if (authentication == null) {
      return Mono.error(
          new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required"));
    }

    return userRepo
      .findByUsername(
          authentication.getName())
      .switchIfEmpty(
          Mono.error(
              new ResponseStatusException(HttpStatus.NOT_FOUND,"Authenticated user not found")))
      .flatMap(user ->
        paymentMethodRepo
            .findById(command.getPaymentMethodId())
            .filter(method ->
              user.getId() != null
                && method.getUser() != null
                && user.getId().equals(method.getUser().getId()))
            .filter(method ->
              method.getPaymentToken() != null && !method
                .getPaymentToken()
                .isEmpty())
            .switchIfEmpty(
              Mono.error(
                new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Valid tokenized payment method required")))
            .flatMap(paymentMethod -> priceOrder(command,user))
            .flatMap(order -> inventoryService.reserve(order)
                .flatMap(reservation -> repo.save(order)
                    .onErrorResume(saveError -> inventoryService
                        .release(reservation.getId())
                        .then(Mono.error(saveError))))
                .flatMap(savedOrder ->
                  Mono.fromRunnable(() -> orderMessages.sendOrder(savedOrder))
                  .thenReturn(savedOrder))));
  }

  private Mono<TacoOrder> priceOrder(OrderCreateCommand command,User user) {

    return priceItems(command.getItems())
        .flatMap(items -> {
          BigDecimal subtotal = subtotal(items);
          CouponApplication coupon = couponService.evaluate(
              command.getCouponCode(),subtotal);

          if (!coupon.isApplicable()) {
            return Mono.error(ApiException.unprocessable(
                "COUPON_NOT_APPLICABLE","Coupon is not applicable."));
          }

          TacoOrder order = ApiMapper.toEntity(command);
          order.setId(UUID.randomUUID().toString());
          order.setUser(user);
          order.setPlacedAt(new Date());
          order.setCurrency(ORDER_CURRENCY);
          items.forEach(order::addItem);
          order.setSubtotal(coupon.getSubtotal());
          order.setAppliedCouponCode(
              coupon.isApplied() ? coupon.getNormalizedCode() : null);
          order.setDiscountAmount(coupon.getDiscount());
          order.setTotal(coupon.getTotal());
          return Mono.just(order);
        });
  }

  public Mono<OrderQuote> quote(OrderQuoteCommand command) {

    return priceItems(command.getItems())
        .map(items -> {
          CouponApplication coupon = couponService.evaluate(
              command.getCouponCode(),subtotal(items));
          List<ClassifiedTaco> classifications = items.stream()
              .map(item -> new ClassifiedTaco(
                  item.getTaco(),item.getTaco().getIngredients(),
                  classificationService.classifyIngredients(
                      item.getTaco().getIngredients())))
              .collect(java.util.stream.Collectors.toList());
          return new OrderQuote(
              coupon.isApplicable(),coupon.getSubtotal(),coupon.getDiscount(),
              coupon.getTotal(),ORDER_CURRENCY,classifications);
        });
  }

  private Mono<List<OrderItem>> priceItems(List<OrderItemCommand> commandItems) {

    List<OrderItemCommand> requestedItems = commandItems != null
        ? commandItems
        : Collections.emptyList();

    if (requestedItems.isEmpty()) {
      return Mono.error(
          ApiException.unprocessable("ORDER_ITEMS_REQUIRED","Order must contain at least one item."));
    }

    return Flux.fromIterable(requestedItems)
        .concatMap(this::priceItem)
        .collectList();
  }

  private BigDecimal subtotal(List<OrderItem> items) {
    return items.stream()
        .map(OrderItem::getSubtotal)
        .reduce(BigDecimal.ZERO,BigDecimal::add)
        .setScale(MONEY_SCALE,MONEY_ROUNDING);
  }

  private Mono<OrderItem> priceItem(OrderItemCommand itemCommand) {

    if (itemCommand == null || itemCommand.getTaco() == null) {
      return Mono.error(
          ApiException.unprocessable("INVALID_ORDER_ITEM","Each item must contain a taco."));
    }

    Integer quantity = itemCommand.getQuantity();
    if (quantity == null || quantity < 1 || quantity > maxQuantity) {
      return Mono.error(
          ApiException.unprocessable(
              "INVALID_ORDER_QUANTITY",
              "Quantity must be between 1 and " + maxQuantity + "."));
    }

    List<String> ingredientIds = itemCommand.getTaco().getIngredientIds() != null
        ? itemCommand.getTaco().getIngredientIds()
        : Collections.emptyList();

    if (ingredientIds.isEmpty()) {
      return Mono.error(
          ApiException.unprocessable("INGREDIENTS_REQUIRED","A taco must contain ingredients."));
    }

    return Flux.fromIterable(ingredientIds)
        .distinct()
        .concatMap(ingredientId -> ingredientRepo.findById(ingredientId)
            .switchIfEmpty(Mono.error(
                ApiException.unprocessable(
                    "INGREDIENT_NOT_FOUND","Ingredient does not exist: " + ingredientId))))
        .collectList()
        .flatMap(ingredients -> {
          TacoDesignValidator.ValidationResult validation =
              designValidator.validateResolved(
                  itemCommand.getTaco().getName(),ingredientIds,ingredients);
          if (!validation.isValid()) {
            return Mono.error(designValidator.invalidDesign(validation));
          }

          boolean invalidCatalogEntry = ingredients.stream()
              .anyMatch(ingredient -> ingredient.getUnitPrice() == null
                  || ingredient.getUnitPrice().signum() < 0);

          if (invalidCatalogEntry) {
            return Mono.error(
                ApiException.unprocessable(
                    "INGREDIENT_PRICE_INVALID","Every ingredient must have a valid price."));
          }

          BigDecimal unitPrice = ingredients.stream()
              .map(Ingredient::getUnitPrice)
              .reduce(BigDecimal.ZERO,BigDecimal::add)
              .setScale(MONEY_SCALE,MONEY_ROUNDING);

          BigDecimal subtotal = unitPrice
              .multiply(BigDecimal.valueOf(quantity))
              .setScale(MONEY_SCALE,MONEY_ROUNDING);

          Taco taco = new Taco();
          taco.setName(itemCommand.getTaco().getName());
          taco.setIngredients(ingredients);

          return Mono.just(new OrderItem(taco,quantity,unitPrice,subtotal));
        });
  }

  /*
   * TC-07
   * Se conserva el flujo de órdenes
   * recibidas por correo.
   */
  public Mono<TacoOrder> createOrderFromEmail(
      Mono<EmailOrder> emailOrder) {

    return emailOrderService
        .convertEmailOrderToDomainOrder(emailOrder)

        .flatMap(repo::save)

        .flatMap(savedOrder ->
            Mono.fromRunnable(() ->
                orderMessages.sendOrder(savedOrder))
                .thenReturn(savedOrder));
  }

  public Flux<TacoOrder> findOrdersFor(Authentication authentication) {

    if (authentication == null) {
      return Flux.empty();
    }

    if (hasRole(authentication,"ROLE_ADMIN")) {
      return repo.findAll();
    }

    return userRepo
        .findByUsername(
            authentication.getName())
        .flatMapMany(user ->
            repo.findByUserOrderByPlacedAtDesc(user,Pageable.unpaged()));
  }

  public boolean canAccessOrder(TacoOrder order,
    Authentication authentication) {

    if (authentication == null) {
      return false;
    }


    if (hasRole(authentication,"ROLE_ADMIN")) {
      return true;
    }

    return order.getUser() != null
        && order.getUser()
            .getUsername() != null
        && order.getUser()
            .getUsername()
            .equals(authentication.getName());
  }

  public Mono<Void> cancelOrder(TacoOrder order) {

    if (order == null || order.getId() == null) {
      return Mono.error(ApiException.notFound(
          "ORDER_NOT_FOUND","Order does not exist."));
    }

    return inventoryService.release(order.getId())
        .then(repo.deleteById(order.getId()));
  }

  private boolean hasRole(Authentication authentication,
      String role) {

    return authentication
        .getAuthorities()
        .stream()
        .anyMatch(authority ->
            role.equals(authority.getAuthority()));
  }
}
