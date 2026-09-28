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
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.dto.ApiDtos.OrderItemRequest;
import tacos.web.api.dto.ApiDtos.OrderQuoteRequest;
import tacos.web.api.dto.ApiDtos.OrderTacoRequest;
import tacos.web.api.mapper.ApiMapper.OrderQuote;
import tacos.web.api.mapper.ApiMapper.OrderQuoteCommand;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
public class OrderApiControllerTest {

  @Mock
  private OrderRepository repo;

  @Mock
  private OrderMessagingService orderMessages;

  @Mock
  private OrderService orderService;

  @InjectMocks
  private OrderApiController controller = 
                        new OrderApiController(repo,orderMessages,orderService);


  /*    TC-04
   Prueba de regresión del ZIP.
   */
  @Test
  public void shouldUpdateZipWithoutChangingState() {

    TacoOrder existingOrder = new TacoOrder();
    existingOrder.setDeliveryState("AGS");
    existingOrder.setDeliveryZip("20000");

    User owner = org.mockito.Mockito.mock(User.class);

    when(owner.getUsername()).thenReturn("jose");

    existingOrder.setUser(owner);

    OrderDeliveryRequest patch = new OrderDeliveryRequest();

    patch.setDeliveryZip("20230");

    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);

    when(authentication.getName()).thenReturn("jose");

    when(authentication.getAuthorities()).thenReturn(Collections.emptyList());

    when(repo.findById("ORDER-1")).thenReturn(Mono.just(existingOrder));

    when(repo.save(existingOrder)).thenReturn(Mono.just(existingOrder));

    StepVerifier.create(
      controller.patchOrder(
        "ORDER-1",
        patch,
        authentication))
        .expectErrorSatisfies(error -> {

        assertTrue(
            error instanceof ApiException);

        ApiException exception = (ApiException) error;

        assertEquals(
            HttpStatus.BAD_REQUEST,
            exception.getStatus());
        })

      .verify();

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

    when(otherUser.getUsername()) .thenReturn("otherUser");

    TacoOrder foreignOrder = new TacoOrder();

    foreignOrder.setUser(otherUser);
    foreignOrder.setDeliveryCity("Mexico City");

    OrderDeliveryRequest patch = new OrderDeliveryRequest();

    patch.setDeliveryCity("Aguascalientes");

    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);

    when(authentication.getName()).thenReturn("jose");

    when(authentication.getAuthorities()).thenReturn(Collections.emptyList());

    when(repo.findById("ORDER-FOREIGN")).thenReturn(Mono.just(foreignOrder));

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
            HttpStatus.BAD_REQUEST,
            exception.getStatus());
      })
      .verify();

    when(repo.findById("ORDER-MISSING")).thenReturn(Mono.empty());

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
            HttpStatus.BAD_REQUEST,
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
        .assertNext(response ->
            assertEquals(
                HttpStatus.BAD_REQUEST,
                response.getStatusCode()))
        .verifyComplete();

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
    when(repo.findById("ORDER-1")).thenReturn(Mono.just(existingOrder));
    when(orderService.canAccessOrder(existingOrder,authentication)).thenReturn(true);
    when(orderService.cancelOrder(existingOrder)).thenReturn(Mono.empty());

    StepVerifier.create(
        controller.deleteOrder(
            "ORDER-1",
            authentication))
        .assertNext(response ->
            assertEquals(HttpStatus.NO_CONTENT,response.getStatusCode()))
        .verifyComplete();

    //La orden no existe.
    when(repo.findById("ORDER-MISSING")).thenReturn(Mono.empty());

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
 
    when(repo.findById("ORDER-FOREIGN")).thenReturn(Mono.just(foreignOrder));

    StepVerifier.create(
        controller.deleteOrder(
            "ORDER-FOREIGN",
            authentication))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof ApiException);
          assertEquals(HttpStatus.FORBIDDEN,((ApiException) error).getStatus());
        })
        .verify();

    verify(orderService).cancelOrder(existingOrder);
    verify(orderService,never()).cancelOrder(foreignOrder);
  }
  
  @Test
  public void shouldNotPhysicallyDeletePreparingOrder() {
    User owner =org.mockito.Mockito.mock(User.class);

    TacoOrder order = new TacoOrder();

    order.setUser(owner);order.setStatus(TacoOrder.Status.PREPARING);

    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);

    when(repo.findById("ORDER-PREPARING")).thenReturn(Mono.just(order));
    when(orderService.canAccessOrder(order,authentication)).thenReturn(true);

    StepVerifier.create(
        controller.deleteOrder(
            "ORDER-PREPARING",
            authentication))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof ApiException);
          assertEquals(HttpStatus.CONFLICT,((ApiException) error).getStatus());
        })
        .verify();

    verify(orderService,never()).cancelOrder(order);
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
            new BigDecimal("18.00"),"MXN")));

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
    verify(orderMessages,never()).sendOrder(any(TacoOrder.class));
  }
}
