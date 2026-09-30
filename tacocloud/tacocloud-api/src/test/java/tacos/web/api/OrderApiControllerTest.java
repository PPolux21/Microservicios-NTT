package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;
import tacos.messaging.OrderEvent;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.dto.ApiDtos.OrderItemRequest;
import tacos.web.api.dto.ApiDtos.OrderCreateRequest;
import tacos.web.api.dto.ApiDtos.OrderQuoteRequest;
import tacos.web.api.dto.ApiDtos.OrderTacoRequest;
import tacos.web.api.dto.ApiDtos.OrderStatusChangeRequest;
import tacos.web.api.dto.ApiDtos.ReorderRequest;
import tacos.web.api.mapper.ApiMapper.OrderQuote;
import tacos.web.api.mapper.ApiMapper.OrderQuoteCommand;
import tacos.web.api.OrderService.ReorderResult;
import tacos.web.api.OrderService.ReorderStatus;
import tacos.web.api.OrderIdempotencyService.PlacementResult;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
public class OrderApiControllerTest {

  @Mock
  private OrderRepository repo;

  @Mock
  private OrderMessagingService orderMessages;

  @Mock
  private OrderService orderService;

  @Mock
  private OrderWorkflowService workflowService;

  @InjectMocks
  private OrderApiController controller = 
      new OrderApiController(repo,orderService,workflowService);

  @Test
  public void shouldReturnCreatedThenOkForIdempotentReplay() {
    OrderCreateRequest request = new OrderCreateRequest();
    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
    TacoOrder order = new TacoOrder();
    order.setId("ORDER-1");

    when(orderService.createOrder(
        any(tacos.web.api.mapper.ApiMapper.OrderCreateCommand.class),
        org.mockito.ArgumentMatchers.eq("order-key-123"),
        org.mockito.ArgumentMatchers.eq(authentication)))
        .thenReturn(Mono.just(new PlacementResult(order,false)))
        .thenReturn(Mono.just(new PlacementResult(order,true)));

    StepVerifier.create(controller.postOrder(
        request,"order-key-123",authentication))
        .assertNext(response -> {
          assertEquals(HttpStatus.CREATED,response.getStatusCode());
          assertEquals("/api/orders/ORDER-1",
              response.getHeaders().getLocation().toString());
          assertEquals("ORDER-1",response.getBody().getId());
        })
        .verifyComplete();

    StepVerifier.create(controller.postOrder(
        request,"order-key-123",authentication))
        .assertNext(response -> {
          assertEquals(HttpStatus.OK,response.getStatusCode());
          assertEquals("ORDER-1",response.getBody().getId());
        })
        .verifyComplete();
  }


  /*    TC-04
   Prueba de regresión del ZIP.
   */
  @Test
  public void shouldUpdateZipWithoutChangingState() {

    TacoOrder existingOrder = new TacoOrder();
    existingOrder.setDeliveryState("AGS");
    existingOrder.setDeliveryZip("20000");

    User owner = org.mockito.Mockito.mock(User.class);

    existingOrder.setUser(owner);

    OrderDeliveryRequest patch = new OrderDeliveryRequest();

    patch.setDeliveryZip("20230");

    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);

    when(orderService.findAccessibleOrder("ORDER-1",authentication))
        .thenReturn(Mono.just(existingOrder));

    when(repo.save(existingOrder)).thenReturn(Mono.just(existingOrder));

    StepVerifier.create(
      controller.patchOrder(
        "ORDER-1",
        patch,
        authentication))
        .assertNext(response -> {
          assertEquals(HttpStatus.OK,response.getStatusCode());
          assertEquals("AGS",existingOrder.getDeliveryState());
          assertEquals("20230",existingOrder.getDeliveryZip());
        })
        .verifyComplete();

    verify(repo).save(existingOrder);
  }


  // Prueba de campo prohibido.
  @Test
  public void shouldReturnBadRequestForUnsupportedField() {
    OrderDeliveryRequest patch = new OrderDeliveryRequest();

    patch.setDeliveryCity("Aguascalientes");

    patch.addUnsupportedField("ccNumber","4111111111111111");

    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);

    StepVerifier.create(
      controller.patchOrder(
        "ORDER-1",
        patch,
        authentication))
      .expectErrorSatisfies(error -> {

        assertTrue(
            error instanceof ApiException);

        ApiException exception =
            (ApiException) error;

        assertEquals(
            HttpStatus.BAD_REQUEST,
            exception.getStatus());
      })

      .verify();

    verify(
      repo,
      never())
      .findById(anyString());

    verify(
      repo,
      never())
      .save(any(TacoOrder.class));
  }


  // Prueba de ownership y 404.
  @Test
  public void shouldEnforceOwnershipAndReturnNotFoundWhenMissing() {
    User otherUser = org.mockito.Mockito.mock(User.class);

    TacoOrder foreignOrder = new TacoOrder();

    foreignOrder.setUser(otherUser);
    foreignOrder.setDeliveryCity("Mexico City");

    OrderDeliveryRequest patch = new OrderDeliveryRequest();

    patch.setDeliveryCity("Aguascalientes");

    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);

    when(orderService.findAccessibleOrder("ORDER-FOREIGN",authentication))
        .thenReturn(Mono.error(ApiException.notFound(
            "ORDER_NOT_FOUND","Order does not exist.")));

    StepVerifier.create(
      controller.patchOrder(
        "ORDER-FOREIGN",
        patch,
        authentication))
      .expectErrorSatisfies(error -> {
        assertTrue(
            error instanceof ApiException);
        ApiException exception =
            (ApiException) error;
        assertEquals(
            HttpStatus.NOT_FOUND,
            exception.getStatus());
      })
      .verify();

    when(orderService.findAccessibleOrder("ORDER-MISSING",authentication))
        .thenReturn(Mono.error(ApiException.notFound(
            "ORDER_NOT_FOUND","Order does not exist.")));

    StepVerifier.create(
      controller.patchOrder(
        "ORDER-MISSING",
        patch,
        authentication))
      .expectErrorSatisfies(error -> {

        assertTrue(
            error instanceof ApiException);

        ApiException exception =
            (ApiException) error;

        assertEquals(
            HttpStatus.NOT_FOUND,
            exception.getStatus());
      })

      .verify();


    verify(
      repo,
      never())
      .save(any(TacoOrder.class));
  }

  // TC-05 PUT y DELETE
  // PUT con IDs contradictorios
  @Test
  public void shouldRejectPutWithBodyId() {

    OrderDeliveryRequest request = new OrderDeliveryRequest();

    request.setDeliveryName("Jose");

    request.addUnsupportedField("id","ORDER-OTHER");

    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);

    StepVerifier.create(
        controller.putOrder(
            "ORDER-1",
            request,
            authentication))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof ApiException);
          assertEquals(HttpStatus.BAD_REQUEST,((ApiException) error).getStatus());
        })
        .verify();

    verify(repo, never()).findById(anyString());

    verify(repo, never()).save(any(TacoOrder.class));
  }

  // DELETE existente/ausente/ajeno
  @Test
  public void shouldHandleDeleteExistingMissingAndForeignOrder() {

    User owner = org.mockito.Mockito.mock(User.class);

    User otherUser = org.mockito.Mockito.mock(User.class);

    TacoOrder existingOrder =new TacoOrder();

    existingOrder.setId("ORDER-1");
    existingOrder.setUser(owner);
    existingOrder.setStatus(TacoOrder.Status.CREATED);

    TacoOrder foreignOrder = new TacoOrder();

    foreignOrder.setUser(otherUser);
    foreignOrder.setStatus(TacoOrder.Status.CREATED);

    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);

    // La orden existe y pertenece al usuario.
    when(workflowService.cancel(
        "ORDER-1","Cancelled through legacy DELETE endpoint",authentication))
        .thenReturn(Mono.just(existingOrder));

    StepVerifier.create(
        controller.deleteOrder(
            "ORDER-1",
            authentication))
        .assertNext(response ->
            assertEquals(HttpStatus.NO_CONTENT,response.getStatusCode()))
        .verifyComplete();

    //La orden no existe.
    when(workflowService.cancel(
        "ORDER-MISSING","Cancelled through legacy DELETE endpoint",authentication))
        .thenReturn(Mono.error(ApiException.notFound(
            "ORDER_NOT_FOUND","Order does not exist.")));

    StepVerifier.create(
      controller.deleteOrder(
        "ORDER-MISSING",
        authentication))
      .expectErrorSatisfies(error -> {
        assertTrue(error instanceof ApiException);
        assertEquals(HttpStatus.NOT_FOUND,((ApiException) error).getStatus());
      })
      .verify();


    // La orden existe pero pertenece a otro usuario.
 
    when(workflowService.cancel(
        "ORDER-FOREIGN","Cancelled through legacy DELETE endpoint",authentication))
        .thenReturn(Mono.error(ApiException.notFound(
            "ORDER_NOT_FOUND","Order does not exist.")));

    StepVerifier.create(
        controller.deleteOrder(
            "ORDER-FOREIGN",
            authentication))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof ApiException);
          assertEquals(HttpStatus.NOT_FOUND,((ApiException) error).getStatus());
        })
        .verify();

    verify(workflowService).cancel(
        "ORDER-1","Cancelled through legacy DELETE endpoint",authentication);
  }
  
  @Test
  public void shouldNotPhysicallyDeletePreparingOrder() {
    User owner =org.mockito.Mockito.mock(User.class);

    TacoOrder order = new TacoOrder();

    order.setUser(owner);order.setStatus(TacoOrder.Status.PREPARING);

    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);

    when(workflowService.cancel(
        "ORDER-PREPARING","Cancelled through legacy DELETE endpoint",authentication))
        .thenReturn(Mono.error(ApiException.conflict(
            "INVALID_ORDER_TRANSITION","Cannot cancel preparing order.")));

    StepVerifier.create(
        controller.deleteOrder(
            "ORDER-PREPARING",
            authentication))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof ApiException);
          assertEquals(HttpStatus.CONFLICT,((ApiException) error).getStatus());
        })
        .verify();

    verify(workflowService).cancel(
        "ORDER-PREPARING","Cancelled through legacy DELETE endpoint",authentication);
  }

  @Test
  public void shouldReturnSafeQuoteWithoutCouponEnumeration()
      throws Exception {

    OrderTacoRequest taco = new OrderTacoRequest();
    taco.setName("Quote Taco");
    taco.setIngredientIds(Collections.singletonList("FLTO"));
    OrderItemRequest item = new OrderItemRequest();
    item.setTaco(taco);
    item.setQuantity(2);
    OrderQuoteRequest request = new OrderQuoteRequest();
    request.setItems(Collections.singletonList(item));
    request.setCouponCode("promo10");

    when(orderService.quote(any(OrderQuoteCommand.class)))
        .thenReturn(Mono.just(new OrderQuote(
            true,new BigDecimal("20.00"),new BigDecimal("2.00"),
            new BigDecimal("18.00"),"MXN",Collections.emptyList())));

    StepVerifier.create(controller.quote(request))
        .assertNext(response -> {
          assertTrue(response.isValid());
          assertEquals(new BigDecimal("2.00"),response.getDiscount());
          String json;
          try {
            json = new ObjectMapper().writeValueAsString(response);
          } catch (Exception exception) {
            throw new AssertionError(exception);
          }
          assertTrue(!json.contains("couponCode"));
          assertTrue(!json.contains("rules"));
          assertTrue(!json.contains("coupons"));
        })
        .verifyComplete();

    verify(repo,never()).save(any(TacoOrder.class));
    verify(orderMessages,never()).sendOrder(any(OrderEvent.class));
  }

  @Test
  public void shouldExposeReorderQuoteWithoutCreatingOrderDirectly()
      throws Exception {
    ReorderRequest request = new ReorderRequest();
    request.setPaymentMethodId("PAY-1");
    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
    when(orderService.reorder(
        "ORDER-1","PAY-1",false,"KEY-1",authentication))
        .thenReturn(Mono.just(new ReorderResult(
            ReorderStatus.REORDER_QUOTE,true,
            new BigDecimal("10.00"),new BigDecimal("12.00"),
            new BigDecimal("2.00"),
            Collections.singletonList("PRICE_CHANGED"),null)));

    StepVerifier.create(controller.reorder(
        "ORDER-1","KEY-1",request,authentication))
        .assertNext(response -> {
          assertEquals("REORDER_QUOTE",response.getStatus());
          assertTrue(response.isRequiresConfirmation());
          assertEquals(new BigDecimal("2.00"),response.getDifference());
          assertEquals(null,response.getOrder());
          try {
            String json = new ObjectMapper().writeValueAsString(response);
            assertTrue(!json.contains("paymentToken"));
            assertTrue(!json.contains("ccNumber"));
            assertTrue(!json.contains("ccCVV"));
          } catch (Exception exception) {
            throw new AssertionError(exception);
          }
        })
        .verifyComplete();

    verify(repo,never()).save(any(TacoOrder.class));
    verify(orderMessages,never()).sendOrder(any(OrderEvent.class));
  }

  @Test
  public void shouldDelegateStatusAndCancellationToCentralWorkflow() {
    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
    TacoOrder accepted = new TacoOrder();
    accepted.setId("ORDER-1");
    accepted.setStatus(TacoOrder.Status.ACCEPTED);
    OrderStatusChangeRequest request = new OrderStatusChangeRequest();
    request.setStatus(TacoOrder.Status.ACCEPTED);
    request.setReason("accepted by kitchen");
    when(workflowService.transition(
        "ORDER-1",TacoOrder.Status.ACCEPTED,
        "accepted by kitchen",authentication))
        .thenReturn(Mono.just(accepted));
    when(workflowService.cancel(
        "ORDER-1","Cancelled by owner",authentication))
        .thenReturn(Mono.just(accepted));

    StepVerifier.create(controller.changeStatus(
        "ORDER-1",request,authentication))
        .assertNext(response -> assertEquals("ACCEPTED",response.getStatus()))
        .verifyComplete();
    StepVerifier.create(controller.cancelOrder("ORDER-1",authentication))
        .expectNextCount(1)
        .verifyComplete();

    verify(workflowService).transition(
        "ORDER-1",TacoOrder.Status.ACCEPTED,
        "accepted by kitchen",authentication);
    verify(workflowService).cancel(
        "ORDER-1","Cancelled by owner",authentication);
  }
}
