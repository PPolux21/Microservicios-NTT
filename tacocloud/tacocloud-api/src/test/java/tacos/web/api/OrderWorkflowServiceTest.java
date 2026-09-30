package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.TacoOrder.ChangeOrigin;
import tacos.TacoOrder.OrderStatusHistoryEntry;
import tacos.TacoOrder.Status;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventType;
import tacos.messaging.OrderMessagingService;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.mapper.ApiMapper;

public class OrderWorkflowServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-29T18:00:00Z");

  private OrderRepository orders;
  private UserRepository users;
  private InventoryService inventory;
  private OrderMessagingService orderMessages;
  private OrderWorkflowService workflow;

  @BeforeEach
  public void setup() {
    orders = Mockito.mock(OrderRepository.class);
    users = Mockito.mock(UserRepository.class);
    inventory = Mockito.mock(InventoryService.class);
    orderMessages = Mockito.mock(OrderMessagingService.class);
    workflow = new OrderWorkflowService(
        orders,users,inventory,Clock.fixed(NOW,ZoneOffset.UTC),orderMessages);
  }

  @ParameterizedTest(name="{0} -> {1} as {2}: allowed={3}")
  @MethodSource("statusMatrix")
  public void shouldEnforceCompleteStatusAndRoleMatrix(
      Status current,Status target,String role,boolean allowed) {
    TacoOrder order = order("ORDER-1","OWNER",current);
    when(orders.findById("ORDER-1")).thenReturn(Mono.just(order));
    when(orders.save(any(TacoOrder.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

    Mono<TacoOrder> result = workflow.transition(
        "ORDER-1",target,"matrix",authentication("actor",role));

    if (allowed) {
      StepVerifier.create(result)
          .assertNext(saved -> assertEquals(target,saved.getStatus()))
          .verifyComplete();
    } else {
      StepVerifier.create(result)
          .expectError(ApiException.class)
          .verify();
    }
  }

  static Stream<Arguments> statusMatrix() {
    List<Arguments> cases = new ArrayList<>();
    List<String> roles = Arrays.asList("ROLE_USER","ROLE_KITCHEN","ROLE_ADMIN");
    for (Status current : Status.values()) {
      for (Status target : Status.values()) {
        for (String role : roles) {
          cases.add(Arguments.of(
              current,target,role,isAllowedOnStatusEndpoint(
                  current,target,role)));
        }
      }
    }
    return cases.stream();
  }

  private static boolean isAllowedOnStatusEndpoint(
      Status current,Status target,String role) {
    if ("ROLE_USER".equals(role) || target == Status.CANCELLED) {
      return false;
    }
    if (current == target) {
      if (target == Status.ACCEPTED || target == Status.PREPARING
          || target == Status.READY) {
        return true;
      }
      return "ROLE_ADMIN".equals(role)
          && (target == Status.OUT_FOR_DELIVERY
              || target == Status.DELIVERED);
    }
    boolean admin = "ROLE_ADMIN".equals(role);
    boolean kitchen = "ROLE_KITCHEN".equals(role);
    if (current == Status.CREATED && target == Status.ACCEPTED
        || current == Status.ACCEPTED && target == Status.PREPARING
        || current == Status.PREPARING && target == Status.READY) {
      return admin || kitchen;
    }
    return admin && (current == Status.READY
            && target == Status.OUT_FOR_DELIVERY
        || current == Status.OUT_FOR_DELIVERY
            && target == Status.DELIVERED);
  }

  @Test
  public void shouldFollowHappyPathAndAppendOrderedSafeAudit() throws Exception {
    TacoOrder order = order("ORDER-1","OWNER",Status.CREATED);
    order.setVersion(0L);
    order.setActiveKitchenStationKey("STATION-A");
    order.addStatusHistory(new OrderStatusHistoryEntry(
        null,Status.CREATED,Date.from(NOW.minusSeconds(60)),"owner",
        ChangeOrigin.USER_API,"Order created"));
    when(orders.findById("ORDER-1")).thenAnswer(ignored -> Mono.just(order));
    when(orders.save(any(TacoOrder.class))).thenAnswer(invocation -> {
      TacoOrder saved = invocation.getArgument(0);
      saved.setVersion(saved.getVersion() + 1);
      return Mono.just(saved);
    });
    String originalOwner = order.getUserId();
    Authentication cook = authentication("cook","ROLE_KITCHEN");

    StepVerifier.create(workflow.transition(
        "ORDER-1",Status.ACCEPTED,"accepted",cook)
        .then(workflow.transition(
            "ORDER-1",Status.PREPARING,"started",cook))
        .then(workflow.transition(
            "ORDER-1",Status.READY,"ready",cook)))
        .assertNext(saved -> {
          assertEquals(Status.READY,saved.getStatus());
          assertEquals(null,saved.getActiveKitchenStationKey());
          assertEquals(3L,saved.getVersion());
          assertEquals(originalOwner,saved.getUserId());
          assertEquals(4,saved.getStatusHistory().size());
          assertEquals(Status.ACCEPTED,
              saved.getStatusHistory().get(1).getToStatus());
          assertEquals(Status.PREPARING,
              saved.getStatusHistory().get(2).getToStatus());
          OrderStatusHistoryEntry last = saved.getStatusHistory().get(3);
          assertEquals(Status.PREPARING,last.getFromStatus());
          assertEquals(Status.READY,last.getToStatus());
          assertEquals("cook",last.getChangedBy());
          assertEquals(ChangeOrigin.KITCHEN_API,last.getOrigin());
          assertNotNull(last.getChangedAt());
          assertEquals("ready",last.getReason());
        })
        .verifyComplete();

    String json = new ObjectMapper().writeValueAsString(
        ApiMapper.toResponse(order));
    assertTrue(json.contains("statusHistory"));
    assertFalse(json.contains("paymentToken"));
    assertFalse(json.contains("ccNumber"));
    assertFalse(json.contains("ccCVV"));
    assertFalse(json.contains("password"));
    assertFalse(json.contains("Authorization"));

    ArgumentCaptor<OrderEvent> events = ArgumentCaptor.forClass(OrderEvent.class);
    verify(orderMessages,times(3)).sendOrder(events.capture());
    assertTrue(events.getAllValues().stream()
        .allMatch(event -> event.getEventType() == OrderEventType.STATUS_CHANGED));
    assertEquals("CREATED",
        events.getAllValues().get(0).getPayload().getPreviousStatus());
    assertEquals("READY",
        events.getAllValues().get(2).getPayload().getStatus());
    assertNotNull(events.getAllValues().get(0).getCorrelationId());
  }

  @Test
  public void shouldRejectCreatedToDeliveredWithoutMutation() {
    TacoOrder order = order("ORDER-1","OWNER",Status.CREATED);
    when(orders.findById("ORDER-1")).thenReturn(Mono.just(order));

    StepVerifier.create(workflow.transition(
        "ORDER-1",Status.DELIVERED,"skip",
        authentication("admin","ROLE_ADMIN")))
        .expectErrorSatisfies(error -> {
          assertEquals(409,((ApiException) error).getStatus().value());
          assertEquals("INVALID_ORDER_TRANSITION",((ApiException) error).getCode());
        })
        .verify();

    assertEquals(Status.CREATED,order.getStatus());
    assertTrue(order.getStatusHistory().isEmpty());
    verify(orders,never()).save(any(TacoOrder.class));
  }

  @Test
  public void shouldEnforceKitchenAndUserRoleLimits() {
    TacoOrder ready = order("ORDER-1","OWNER",Status.READY);
    when(orders.findById("ORDER-1")).thenReturn(Mono.just(ready));

    StepVerifier.create(workflow.transition(
        "ORDER-1",Status.OUT_FOR_DELIVERY,"dispatch",
        authentication("cook","ROLE_KITCHEN")))
        .expectErrorSatisfies(error -> assertEquals(
            403,((ApiException) error).getStatus().value()))
        .verify();
    StepVerifier.create(workflow.transition(
        "ORDER-1",Status.DELIVERED,"delivered",
        authentication("alice","ROLE_USER")))
        .expectErrorSatisfies(error -> assertEquals(
            403,((ApiException) error).getStatus().value()))
        .verify();

    verify(orders,never()).save(any(TacoOrder.class));
  }

  @Test
  public void shouldTreatRepeatedAuthorizedTransitionAsIdempotent() {
    TacoOrder preparing = order("ORDER-1","OWNER",Status.PREPARING);
    preparing.addStatusHistory(new OrderStatusHistoryEntry(
        Status.ACCEPTED,Status.PREPARING,Date.from(NOW),"cook",
        ChangeOrigin.KITCHEN_API,"started"));
    when(orders.findById("ORDER-1")).thenReturn(Mono.just(preparing));

    StepVerifier.create(workflow.transition(
        "ORDER-1",Status.PREPARING,"retry",
        authentication("cook","ROLE_KITCHEN")))
        .assertNext(order -> assertEquals(1,order.getStatusHistory().size()))
        .verifyComplete();

    verify(orders,never()).save(any(TacoOrder.class));
  }

  @Test
  public void shouldCancelOwnerBeforeCutoffAndRemainIdempotent() {
    User owner = user("OWNER","alice");
    TacoOrder order = order("ORDER-1",owner.getId(),Status.ACCEPTED);
    order.setActiveKitchenStationKey("station-01");
    when(users.findByUsername("alice")).thenReturn(Mono.just(owner));
    when(orders.findByIdAndUserId("ORDER-1","OWNER"))
        .thenAnswer(ignored -> Mono.just(order));
    when(orders.save(any(TacoOrder.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(inventory.release("ORDER-1")).thenReturn(Mono.empty());
    Authentication alice = authentication("alice","ROLE_USER");

    StepVerifier.create(workflow.cancel("ORDER-1","changed mind",alice)
        .then(workflow.cancel("ORDER-1","changed mind",alice)))
        .assertNext(cancelled -> {
          assertEquals(Status.CANCELLED,cancelled.getStatus());
          assertEquals(1,cancelled.getStatusHistory().size());
          assertEquals("alice",cancelled.getStatusHistory().get(0).getChangedBy());
          assertEquals(null,cancelled.getActiveKitchenStationKey());
        })
        .verifyComplete();

    verify(orders,times(1)).save(order);
    verify(inventory,times(2)).release("ORDER-1");
    ArgumentCaptor<OrderEvent> event = ArgumentCaptor.forClass(OrderEvent.class);
    verify(orderMessages).sendOrder(event.capture());
    assertEquals(OrderEventType.CANCELLED,event.getValue().getEventType());
    assertEquals("ACCEPTED",event.getValue().getPayload().getPreviousStatus());
    assertEquals("CANCELLED",event.getValue().getPayload().getStatus());
    assertEquals("changed mind",
        event.getValue().getPayload().getCancellationReason());
  }

  @Test
  public void shouldRejectCancellationAfterCutoffAndHideForeignOrder() {
    User alice = user("OWNER-A","alice");
    when(users.findByUsername("alice")).thenReturn(Mono.just(alice));
    TacoOrder preparing = order("ORDER-1","OWNER-A",Status.PREPARING);
    when(orders.findByIdAndUserId("ORDER-1","OWNER-A"))
        .thenReturn(Mono.just(preparing));
    when(orders.findByIdAndUserId("ORDER-B","OWNER-A"))
        .thenReturn(Mono.empty());
    Authentication authentication = authentication("alice","ROLE_USER");

    StepVerifier.create(workflow.cancel(
        "ORDER-1","too late",authentication))
        .expectErrorSatisfies(error -> assertEquals(
            "INVALID_ORDER_TRANSITION",((ApiException) error).getCode()))
        .verify();
    StepVerifier.create(workflow.cancel(
        "ORDER-B","foreign",authentication))
        .expectErrorSatisfies(error -> assertEquals(
            404,((ApiException) error).getStatus().value()))
        .verify();

    verify(orders,never()).save(any(TacoOrder.class));
    verify(inventory,never()).release(any(String.class));
  }

  @Test
  public void shouldMapOptimisticLockingToConflict() {
    TacoOrder order = order("ORDER-1","OWNER",Status.CREATED);
    when(orders.findById("ORDER-1")).thenReturn(Mono.just(order));
    when(orders.save(order)).thenReturn(Mono.error(
        new OptimisticLockingFailureException("stale version")));

    StepVerifier.create(workflow.transition(
        "ORDER-1",Status.ACCEPTED,"accept",
        authentication("cook","ROLE_KITCHEN")))
        .expectErrorSatisfies(error -> {
          assertEquals(409,((ApiException) error).getStatus().value());
          assertEquals("ORDER_VERSION_CONFLICT",((ApiException) error).getCode());
        })
        .verify();
  }

  private TacoOrder order(String id,String ownerId,Status status) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setUserId(ownerId);
    order.setStatus(status);
    return order;
  }

  private User user(String id,String username) {
    User user = new User(username,"{noop}secret",username,"street","city",
        "state","00000","000",username + "@example.test");
    user.setId(id);
    return user;
  }

  private Authentication authentication(String username,String role) {
    return new UsernamePasswordAuthenticationToken(
        username,"password",AuthorityUtils.createAuthorityList(role));
  }
}
