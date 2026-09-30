package tacos.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.dao.DuplicateKeyException;

import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.actuator.TacoMetrics;
import tacos.Ingredient;
import tacos.ReorderAttempt;
import tacos.ReorderAttempt.Status;
import tacos.Taco;
import tacos.User;
import tacos.TacoOrder;
import tacos.TacoOrder.ChangeOrigin;
import tacos.TacoOrder.OrderItem;
import tacos.TacoOrder.OrderStatusHistoryEntry;
import tacos.web.api.mapper.ApiMapper;
import tacos.web.api.mapper.ApiMapper.OrderCreateCommand;
import tacos.web.api.mapper.ApiMapper.OrderItemCommand;
import tacos.web.api.mapper.ApiMapper.OrderQuote;
import tacos.web.api.mapper.ApiMapper.OrderQuoteCommand;
import tacos.web.api.mapper.ApiMapper.TacoCommand;
import tacos.web.api.outbox.OrderOutboxService;
import tacos.web.api.coupon.CouponService;
import tacos.web.api.coupon.CouponService.CouponApplication;
import tacos.web.api.TacoClassificationService.ClassifiedTaco;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.ReorderAttemptRepository;
import tacos.data.UserRepository;
import lombok.AllArgsConstructor;
import lombok.Data;

@Service
public class OrderService {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
  private static final String ORDER_CURRENCY = "MXN";

  private OrderRepository repo;
  private OrderOutboxService orderOutbox;
  private EmailOrderService emailOrderService;
  private UserRepository userRepo;
  private PaymentMethodRepository paymentMethodRepo;
  private IngredientRepository ingredientRepo;
  private CouponService couponService;
  private InventoryService inventoryService;
  private TacoClassificationService classificationService;
  private TacoDesignValidator designValidator;
  private ReorderAttemptRepository reorderAttemptRepo;
  private OrderIdempotencyService idempotency;
  private OrderRequestHasher requestHasher;
  private TacoMetrics metrics;
  private int maxQuantity = 10;

  public OrderService(
      OrderRepository repo,
      OrderOutboxService orderOutbox,
      EmailOrderService emailOrderService,
      UserRepository userRepo,
      PaymentMethodRepository paymentMethodRepo,
      IngredientRepository ingredientRepo,
      CouponService couponService,
      InventoryService inventoryService,
      TacoClassificationService classificationService,
      TacoDesignValidator designValidator,
      ReorderAttemptRepository reorderAttemptRepo,
      OrderIdempotencyService idempotency,
      OrderRequestHasher requestHasher,
      TacoMetrics metrics) {

    this.repo = repo;
    this.orderOutbox = orderOutbox;
    this.emailOrderService = emailOrderService;
    this.userRepo = userRepo;
    this.paymentMethodRepo = paymentMethodRepo;
    this.ingredientRepo = ingredientRepo;
    this.couponService = couponService;
    this.inventoryService = inventoryService;
    this.classificationService = classificationService;
    this.designValidator = designValidator;
    this.reorderAttemptRepo = reorderAttemptRepo;
    this.idempotency = idempotency;
    this.requestHasher = requestHasher;
    this.metrics = metrics;
  }

  @Value("${tacocloud.orders.max-quantity:10}")
  void configureMaxQuantity(int maxQuantity) {
    this.maxQuantity = maxQuantity;
  }

  public Mono<TacoOrder> createOrder(OrderCreateCommand command, 
      Authentication authentication) {

    return createOrder(command,authentication,null);
  }

  public Mono<OrderIdempotencyService.PlacementResult> createOrder(
      OrderCreateCommand command,String idempotencyKey,
      Authentication authentication) {
    idempotency.validateKey(idempotencyKey);
    String requestHash = requestHasher.hash(command);
    return currentUser(authentication)
        .flatMap(user -> idempotency.execute(
            user.getId(),idempotencyKey,requestHash,
            orderId -> createOrder(command,authentication,orderId)));
  }

  private Mono<TacoOrder> createOrder(OrderCreateCommand command,
      Authentication authentication,String requestedOrderId) {

    return Mono.defer(() -> {
      Timer.Sample sample = metrics.startOrderPlacement();
      return createOrderAttempt(command,authentication,requestedOrderId)
          .doOnSuccess(order -> metrics.orderPlacementFinished(sample,"success"))
          .doOnError(error -> {
            metrics.orderFailed();
            metrics.orderPlacementFinished(sample,"failure");
          });
    });
  }

  private Mono<TacoOrder> createOrderAttempt(OrderCreateCommand command,
      Authentication authentication,String requestedOrderId) {

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
            .flatMap(paymentMethod -> priceOrder(
                command,user,requestedOrderId,authentication))
            .flatMap(order -> inventoryService.reserve(order)
                .flatMap(reservation -> CorrelationIdWebFilter
                    .currentCorrelationId()
                    .flatMap(correlationId -> orderOutbox.saveCreated(
                        order,correlationId))
                    .onErrorResume(saveError -> inventoryService
                        .release(reservation.getId())
                        .then(Mono.error(saveError))))));
  }

  private Mono<TacoOrder> priceOrder(OrderCreateCommand command,User user,
      String requestedOrderId,Authentication authentication) {

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
          order.setId(requestedOrderId != null
              ? requestedOrderId : UUID.randomUUID().toString());
          order.setUser(user);
          Date placedAt = new Date();
          order.setPlacedAt(placedAt);
          order.setStatus(TacoOrder.Status.CREATED);
          order.addStatusHistory(new OrderStatusHistoryEntry(
              null,TacoOrder.Status.CREATED,placedAt,user.getUsername(),
              hasRole(authentication,"ROLE_ADMIN")
                  ? ChangeOrigin.ADMIN_API : ChangeOrigin.USER_API,
              "Order created"));
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

  public Mono<ReorderResult> reorder(String originalOrderId,
      String paymentMethodId,boolean confirmPriceChange,
      String idempotencyKey,Authentication authentication) {
    if (idempotencyKey == null || idempotencyKey.trim().isEmpty()
        || idempotencyKey.length() > 200) {
      return Mono.error(ApiException.badRequest(
          "IDEMPOTENCY_KEY_REQUIRED",
          "A valid Idempotency-Key is required for reorder."));
    }

    return currentUser(authentication)
        .flatMap(user -> repo.findByIdAndUserId(originalOrderId,user.getId())
            .switchIfEmpty(Mono.error(ApiException.notFound(
                "ORDER_NOT_FOUND","Order does not exist.")))
            .flatMap(original -> {
              String scope = user.getId() + "\u0000" + originalOrderId
                  + "\u0000" + idempotencyKey.trim();
              String attemptId = stableId("reorder-attempt",scope);
              String newOrderId = stableId("reorder-order",scope);
              return existingReorder(attemptId,user.getId(),original)
                  .switchIfEmpty(Mono.defer(() -> {
                    OrderCreateCommand command = rebuildCommand(
                        original,paymentMethodId);
                    validateDelivery(command);
                    return priceReorder(command)
                        .flatMap(pricing -> finishReorder(
                            original,command,pricing,confirmPriceChange,
                            authentication,attemptId,newOrderId,user.getId()));
                  }));
            }));
  }

  private Mono<ReorderResult> finishReorder(TacoOrder original,
      OrderCreateCommand command,ReorderPricing pricing,
      boolean confirmPriceChange,Authentication authentication,
      String attemptId,String newOrderId,String userId) {
    BigDecimal originalTotal = money(original.getTotal());
    BigDecimal currentTotal = money(pricing.getQuote().getTotal());
    BigDecimal difference = currentTotal.subtract(originalTotal)
        .setScale(MONEY_SCALE,MONEY_ROUNDING);
    List<String> differences = new ArrayList<>(pricing.getDifferences());
    if (difference.signum() != 0 && !differences.contains("PRICE_CHANGED")) {
      differences.add("PRICE_CHANGED");
    }
    boolean requiresConfirmation = !differences.isEmpty();

    if (requiresConfirmation && !confirmPriceChange) {
      return Mono.just(new ReorderResult(
          ReorderStatus.REORDER_QUOTE,true,originalTotal,currentTotal,
          difference,differences,null));
    }

    command.setCouponCode(pricing.getEffectiveCouponCode());
    ReorderAttempt attempt = new ReorderAttempt(
        attemptId,userId,original.getId(),newOrderId,Status.PENDING);
    ReorderPricing finalPricing = new ReorderPricing(
        pricing.getQuote(),pricing.getEffectiveCouponCode(),differences);

    return reorderAttemptRepo.insert(attempt)
        .flatMap(claimed -> createOrder(command,authentication,newOrderId)
            .flatMap(created -> markCreated(claimed)
                .thenReturn(createdResult(original,created,finalPricing)))
            .onErrorResume(error -> recoverCreatedOrder(
                claimed,original,finalPricing,error)))
        .onErrorResume(DuplicateKeyException.class,
            error -> existingReorder(attemptId,userId,original)
                .switchIfEmpty(Mono.error(ApiException.conflict(
                    "REORDER_IN_PROGRESS","Reorder is still in progress."))));
  }

  private Mono<ReorderResult> recoverCreatedOrder(ReorderAttempt attempt,
      TacoOrder original,ReorderPricing pricing,Throwable originalError) {
    return repo.findByIdAndUserId(attempt.getNewOrderId(),attempt.getUserId())
        .flatMap(created -> markCreated(attempt)
            .then(Mono.<ReorderResult>error(originalError)))
        .switchIfEmpty(Mono.defer(() -> reorderAttemptRepo
            .deleteById(attempt.getId())
            .then(Mono.<ReorderResult>error(originalError))));
  }

  private Mono<ReorderResult> existingReorder(String attemptId,
      String userId,TacoOrder original) {
    return reorderAttemptRepo.findById(attemptId)
        .flatMap(attempt -> {
          if (!userId.equals(attempt.getUserId())) {
            return Mono.error(ApiException.conflict(
                "REORDER_KEY_CONFLICT","Idempotency key has another owner."));
          }
          return repo.findByIdAndUserId(attempt.getNewOrderId(),userId)
              .map(created -> createdResult(
                  original,created,pricingFromCreated(original,created)))
              .switchIfEmpty(Mono.error(ApiException.conflict(
                  "REORDER_IN_PROGRESS","Reorder is still in progress.")));
        });
  }

  private Mono<Void> markCreated(ReorderAttempt attempt) {
    attempt.setStatus(Status.CREATED);
    return reorderAttemptRepo.save(attempt).then();
  }

  private ReorderResult createdResult(TacoOrder original,TacoOrder created,
      ReorderPricing pricing) {
    BigDecimal originalTotal = money(original.getTotal());
    BigDecimal currentTotal = money(created.getTotal());
    return new ReorderResult(
        ReorderStatus.REORDER_CREATED,false,originalTotal,currentTotal,
        currentTotal.subtract(originalTotal)
            .setScale(MONEY_SCALE,MONEY_ROUNDING),
        new ArrayList<>(pricing.getDifferences()),created);
  }

  private ReorderPricing pricingFromCreated(TacoOrder original,
      TacoOrder created) {
    List<String> differences = new ArrayList<>();
    if (money(created.getTotal()).compareTo(money(original.getTotal())) != 0) {
      differences.add("PRICE_CHANGED");
    }
    if (original.getAppliedCouponCode() != null
        && created.getAppliedCouponCode() == null) {
      differences.add("COUPON_NOT_APPLICABLE");
    }
    return new ReorderPricing(null,created.getAppliedCouponCode(),differences);
  }

  private Mono<ReorderPricing> priceReorder(OrderCreateCommand command) {
    OrderQuoteCommand requestedCoupon = new OrderQuoteCommand(
        command.getItems(),command.getCouponCode());
    return quote(requestedCoupon)
        .flatMap(currentQuote -> {
          if (currentQuote.isValid()) {
            return Mono.just(new ReorderPricing(
                currentQuote,command.getCouponCode(),Collections.emptyList()));
          }
          return quote(new OrderQuoteCommand(command.getItems(),null))
              .map(withoutCoupon -> new ReorderPricing(
                  withoutCoupon,null,
                  Collections.singletonList("COUPON_NOT_APPLICABLE")));
        });
  }

  private OrderCreateCommand rebuildCommand(TacoOrder original,
      String paymentMethodId) {
    List<OrderItem> historicalItems = original.getItems() != null
        ? original.getItems() : Collections.emptyList();
    List<OrderItemCommand> items = historicalItems.stream()
        .map(item -> {
          Taco historicalTaco = item.getTaco();
          List<String> ingredientIds = historicalTaco != null
              && historicalTaco.getIngredients() != null
              ? historicalTaco.getIngredients().stream()
                  .map(Ingredient::getId)
                  .collect(java.util.stream.Collectors.toList())
              : Collections.emptyList();
          return new OrderItemCommand(
              new TacoCommand(
                  historicalTaco != null ? historicalTaco.getName() : null,
                  ingredientIds),
              item.getQuantity());
        })
        .collect(java.util.stream.Collectors.toList());

    return new OrderCreateCommand(
        original.getDeliveryName(),original.getDeliveryStreet(),
        original.getDeliveryCity(),original.getDeliveryState(),
        original.getDeliveryZip(),paymentMethodId,items,
        original.getAppliedCouponCode());
  }

  private void validateDelivery(OrderCreateCommand command) {
    if (isBlank(command.getDeliveryName())
        || isBlank(command.getDeliveryStreet())
        || isBlank(command.getDeliveryCity())
        || isBlank(command.getDeliveryState())
        || isBlank(command.getDeliveryZip())
        || command.getDeliveryName().length() > 50
        || command.getDeliveryStreet().length() > 100
        || command.getDeliveryCity().length() > 50
        || command.getDeliveryState().length() < 2
        || command.getDeliveryState().length() > 50
        || !command.getDeliveryZip().matches("[A-Za-z0-9 -]{3,10}")) {
      throw ApiException.unprocessable(
          "DELIVERY_DATA_INVALID","Historical delivery data is no longer valid.");
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private String stableId(String namespace,String scope) {
    return UUID.nameUUIDFromBytes(
        (namespace + "\u0000" + scope).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private BigDecimal money(BigDecimal value) {
    return (value != null ? value : BigDecimal.ZERO)
        .setScale(MONEY_SCALE,MONEY_ROUNDING);
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

    return Mono.defer(() -> {
      Timer.Sample sample = metrics.startOrderPlacement();
      return emailOrderService
        .convertEmailOrderToDomainOrder(emailOrder)

        .map(order -> {
          order.setStatus(TacoOrder.Status.CREATED);
          if (order.getStatusHistory() == null
              || order.getStatusHistory().isEmpty()) {
            order.addStatusHistory(new OrderStatusHistoryEntry(
                null,TacoOrder.Status.CREATED,new Date(),"email-integration",
                ChangeOrigin.SYSTEM,"Order created from email"));
          }
          return order;
        })

        .flatMap(order -> CorrelationIdWebFilter.currentCorrelationId()
            .flatMap(correlationId -> orderOutbox.saveCreated(
                order,correlationId)))
        .doOnSuccess(order -> metrics.orderPlacementFinished(sample,"success"))
        .doOnError(error -> {
          metrics.orderFailed();
          metrics.orderPlacementFinished(sample,"failure");
        });
    });
  }

  public boolean canAccessOrder(TacoOrder order,
    Authentication authentication) {

    if (authentication == null) {
      return false;
    }


    if (hasRole(authentication,"ROLE_ADMIN")) {
      return true;
    }

    if (order.getUser() != null
        && order.getUser().getUsername() != null) {
      return order.getUser().getUsername().equals(authentication.getName());
    }
    return false;
  }

  public Mono<OrderHistoryPage> findOwnOrderHistory(
      Authentication authentication,int page,int size) {
    return currentUser(authentication)
        .flatMap(user -> findOrderHistory(user.getId(),page,size));
  }

  public Mono<TacoOrder> findOwnOrder(String orderId,
      Authentication authentication) {
    return currentUser(authentication)
        .flatMap(user -> repo.findByIdAndUserId(orderId,user.getId()))
        .switchIfEmpty(Mono.error(ApiException.notFound(
            "ORDER_NOT_FOUND","Order does not exist.")));
  }

  public Mono<OrderHistoryPage> findAdminOrderHistory(
      String userId,int page,int size) {
    Pageable pageable = historyPageable(page,size);
    Flux<TacoOrder> orders = userId == null || userId.trim().isEmpty()
        ? repo.findAllBy(pageable)
        : repo.findByUserId(userId.trim(),pageable);
    Mono<Long> count = userId == null || userId.trim().isEmpty()
        ? repo.count()
        : repo.countByUserId(userId.trim());
    return orders.collectList().zipWith(count)
        .map(result -> new OrderHistoryPage(
            result.getT1(),page,size,result.getT2()));
  }

  public Mono<TacoOrder> findAccessibleOrder(String orderId,
      Authentication authentication) {
    if (authentication != null && hasRole(authentication,"ROLE_ADMIN")) {
      return repo.findById(orderId)
          .switchIfEmpty(Mono.error(ApiException.notFound(
              "ORDER_NOT_FOUND","Order does not exist.")));
    }
    return findOwnOrder(orderId,authentication);
  }

  private Mono<OrderHistoryPage> findOrderHistory(
      String userId,int page,int size) {
    Pageable pageable = historyPageable(page,size);
    return repo.findByUserId(userId,pageable).collectList()
        .zipWith(repo.countByUserId(userId))
        .map(result -> new OrderHistoryPage(
            result.getT1(),page,size,result.getT2()));
  }

  private Pageable historyPageable(int page,int size) {
    Sort sort = Sort.by(Sort.Direction.DESC,"placedAt")
        .and(Sort.by(Sort.Direction.ASC,"id"));
    return PageRequest.of(page,size,sort);
  }

  private Mono<User> currentUser(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return Mono.error(new ApiException(
          HttpStatus.UNAUTHORIZED,"AUTHENTICATION_REQUIRED",
          "Authentication is required."));
    }
    return userRepo.findByUsername(authentication.getName())
        .switchIfEmpty(Mono.error(ApiException.notFound(
            "USER_NOT_FOUND","Authenticated user does not exist.")));
  }

  private boolean hasRole(Authentication authentication,
      String role) {

    return authentication
        .getAuthorities()
        .stream()
        .anyMatch(authority ->
            role.equals(authority.getAuthority()));
  }

  @Data
  @AllArgsConstructor
  public static class OrderHistoryPage {
    private List<TacoOrder> items;
    private int page;
    private int size;
    private long totalElements;

    public int getTotalPages() {
      return size == 0
          ? 0
          : (int) ((totalElements + size - 1) / size);
    }
  }

  public enum ReorderStatus {
    REORDER_QUOTE,
    REORDER_CREATED
  }

  @Data
  @AllArgsConstructor
  public static class ReorderResult {
    private ReorderStatus status;
    private boolean requiresConfirmation;
    private BigDecimal originalTotal;
    private BigDecimal currentTotal;
    private BigDecimal difference;
    private List<String> differences;
    private TacoOrder order;
  }

  @Data
  @AllArgsConstructor
  private static class ReorderPricing {
    private OrderQuote quote;
    private String effectiveCouponCode;
    private List<String> differences;
  }
}
