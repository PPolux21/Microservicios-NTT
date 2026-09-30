package tacos.web.api;

import java.time.Clock;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.TacoOrder.ChangeOrigin;
import tacos.TacoOrder.OrderStatusHistoryEntry;
import tacos.TacoOrder.Status;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.outbox.OrderOutboxService;

@Service
public class OrderWorkflowService {

  private static final String USER = "ROLE_USER";
  private static final String ADMIN = "ROLE_ADMIN";
  private static final String KITCHEN = "ROLE_KITCHEN";

  private static final Map<Status,Map<Status,Set<String>>> TRANSITIONS =
      transitions();

  private final OrderRepository orders;
  private final UserRepository users;
  private final InventoryService inventory;
  private final Clock clock;
  private final OrderOutboxService orderOutbox;

  public OrderWorkflowService(OrderRepository orders,UserRepository users,
      InventoryService inventory,Clock clock,
      OrderOutboxService orderOutbox) {
    this.orders = orders;
    this.users = users;
    this.inventory = inventory;
    this.clock = clock;
    this.orderOutbox = orderOutbox;
  }

  public Mono<TacoOrder> transition(String orderId,Status target,
      String reason,Authentication authentication) {
    requireAuthentication(authentication);
    if (target == null) {
      return Mono.error(ApiException.badRequest(
          "ORDER_STATUS_REQUIRED","A target order status is required."));
    }
    if (target == Status.CANCELLED) {
      return Mono.error(ApiException.conflict(
          "INVALID_ORDER_TRANSITION",
          "Cancellation must use the owner cancellation operation."));
    }
    if (!hasAnyRole(authentication,ADMIN,KITCHEN)) {
      return Mono.error(ApiException.forbidden(
          "ORDER_TRANSITION_FORBIDDEN",
          "The authenticated actor cannot change order status."));
    }

    return orders.findById(orderId)
        .switchIfEmpty(Mono.error(ApiException.notFound(
            "ORDER_NOT_FOUND","Order does not exist.")))
        .flatMap(order -> apply(
            order,target,reason,authentication,false));
  }

  public Mono<TacoOrder> cancel(String orderId,String reason,
      Authentication authentication) {
    requireAuthentication(authentication);
    if (!hasAnyRole(authentication,USER,ADMIN)) {
      return Mono.error(ApiException.forbidden(
          "ORDER_CANCEL_FORBIDDEN","Only an order owner can cancel."));
    }

    return users.findByUsername(authentication.getName())
        .switchIfEmpty(Mono.error(ApiException.notFound(
            "USER_NOT_FOUND","Authenticated user does not exist.")))
        .flatMap(user -> orders.findByIdAndUserId(orderId,user.getId()))
        .switchIfEmpty(Mono.error(ApiException.notFound(
            "ORDER_NOT_FOUND","Order does not exist.")))
        .flatMap(order -> apply(
            order,Status.CANCELLED,reason,authentication,true));
  }

  public void validateTransition(Status current,Status target,
      Authentication authentication) {
    requireAuthentication(authentication);
    if (current == null || target == null) {
      throw ApiException.badRequest(
          "ORDER_STATUS_REQUIRED","Current and target status are required.");
    }
    Set<String> allowedRoles = rolesFor(current,target);
    if (allowedRoles.isEmpty()) {
      throw invalidTransition(current,target);
    }
    if (!hasAnyRole(authentication,allowedRoles)) {
      throw forbiddenTransition();
    }
  }

  private Mono<TacoOrder> apply(TacoOrder order,Status target,
      String reason,Authentication authentication,boolean ownerCancellation) {
    Status current = order.getStatus() != null
        ? order.getStatus() : Status.CREATED;

    if (current == target) {
      if (target == Status.CANCELLED && ownerCancellation) {
        return inventory.release(order.getId()).thenReturn(order);
      }
      Set<String> retryRoles = inboundRoles(target);
      if (retryRoles.isEmpty()) {
        return Mono.error(invalidTransition(current,target));
      }
      if (!hasAnyRole(authentication,retryRoles)) {
        return Mono.error(forbiddenTransition());
      }
      return Mono.just(order);
    }

    boolean operationMatches = target == Status.CANCELLED
        ? ownerCancellation : !ownerCancellation;
    if (!operationMatches) {
      return Mono.error(invalidTransition(current,target));
    }
    try {
      validateTransition(current,target,authentication);
    } catch (ApiException error) {
      return Mono.error(error);
    }

    String normalizedReason = normalizeReason(reason);
    order.setStatus(target);
    order.addStatusHistory(new OrderStatusHistoryEntry(
        current,target,Date.from(clock.instant()),authentication.getName(),
        origin(authentication),normalizedReason));
    if (target == Status.READY || target == Status.CANCELLED) {
      order.setActiveKitchenStationKey(null);
    }

    return orderOutbox.saveStatusChanged(
            order,current,UUID.randomUUID().toString(),normalizedReason)
        .onErrorMap(OptimisticLockingFailureException.class,error ->
            ApiException.conflict(
                "ORDER_VERSION_CONFLICT",
                "The order was updated concurrently. Reload and retry."))
        .flatMap(saved -> target == Status.CANCELLED
            ? inventory.release(saved.getId()).thenReturn(saved)
            : Mono.just(saved));
  }

  private Set<String> rolesFor(Status current,Status target) {
    Map<Status,Set<String>> targets = TRANSITIONS.get(current);
    return targets != null
        ? targets.getOrDefault(target,Collections.emptySet())
        : Collections.emptySet();
  }

  private Set<String> inboundRoles(Status target) {
    java.util.Set<String> roles = new java.util.HashSet<>();
    TRANSITIONS.values().stream()
        .map(targets -> targets.get(target))
        .filter(allowed -> allowed != null)
        .forEach(roles::addAll);
    return roles;
  }

  private ApiException invalidTransition(Status current,Status target) {
    return ApiException.conflict(
        "INVALID_ORDER_TRANSITION",
        "Transition " + current + " -> " + target + " is not allowed.");
  }

  private ApiException forbiddenTransition() {
    return ApiException.forbidden(
        "ORDER_TRANSITION_FORBIDDEN",
        "The authenticated actor cannot perform this transition.");
  }

  private String normalizeReason(String reason) {
    if (reason == null || reason.trim().isEmpty()) {
      return null;
    }
    String normalized = reason.trim();
    if (normalized.length() > 200) {
      throw ApiException.badRequest(
          "ORDER_STATUS_REASON_INVALID",
          "Status change reason must have at most 200 characters.");
    }
    return normalized;
  }

  private void requireAuthentication(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new ApiException(
          org.springframework.http.HttpStatus.UNAUTHORIZED,
          "AUTHENTICATION_REQUIRED","Authentication is required.");
    }
  }

  private boolean hasAnyRole(Authentication authentication,String... roles) {
    for (String role : roles) {
      if (hasRole(authentication,role)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasAnyRole(Authentication authentication,Set<String> roles) {
    return roles.stream().anyMatch(role -> hasRole(authentication,role));
  }

  private boolean hasRole(Authentication authentication,String role) {
    return authentication.getAuthorities().stream()
        .anyMatch(authority -> role.equals(authority.getAuthority()));
  }

  private ChangeOrigin origin(Authentication authentication) {
    if (hasRole(authentication,ADMIN)) {
      return ChangeOrigin.ADMIN_API;
    }
    if (hasRole(authentication,KITCHEN)) {
      return ChangeOrigin.KITCHEN_API;
    }
    return ChangeOrigin.USER_API;
  }

  private static Map<Status,Map<Status,Set<String>>> transitions() {
    Map<Status,Map<Status,Set<String>>> matrix = new EnumMap<>(Status.class);
    allow(matrix,Status.CREATED,Status.ACCEPTED,KITCHEN,ADMIN);
    allow(matrix,Status.ACCEPTED,Status.PREPARING,KITCHEN,ADMIN);
    allow(matrix,Status.PREPARING,Status.READY,KITCHEN,ADMIN);
    allow(matrix,Status.READY,Status.OUT_FOR_DELIVERY,ADMIN);
    allow(matrix,Status.OUT_FOR_DELIVERY,Status.DELIVERED,ADMIN);
    allow(matrix,Status.CREATED,Status.CANCELLED,USER,ADMIN);
    allow(matrix,Status.ACCEPTED,Status.CANCELLED,USER,ADMIN);
    return Collections.unmodifiableMap(matrix);
  }

  private static void allow(Map<Status,Map<Status,Set<String>>> matrix,
      Status from,Status to,String... roles) {
    Map<Status,Set<String>> targets = matrix.computeIfAbsent(
        from,ignored -> new EnumMap<>(Status.class));
    Set<String> allowed = new java.util.HashSet<>();
    Collections.addAll(allowed,roles);
    targets.put(to,Collections.unmodifiableSet(allowed));
  }
}
