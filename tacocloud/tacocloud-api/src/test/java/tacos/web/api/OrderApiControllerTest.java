package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

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

@ExtendWith(MockitoExtension.class)
public class OrderApiControllerTest {

  @Mock
  private OrderRepository repo;

  @Mock
  private OrderMessagingService orderMessages;

  @Mock
  private EmailOrderService emailOrderService;

  @InjectMocks
  private OrderApiController controller;


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

    OrderPatchRequest patch = new OrderPatchRequest();

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
      .assertNext(response -> {
        assertEquals(
          HttpStatus.OK,
          response.getStatusCode());

        assertEquals(
          "20230",
          response.getBody().getDeliveryZip());

        assertEquals(
          "AGS",
          response.getBody().getDeliveryState());
      })
      .verifyComplete();

    verify(repo).save(existingOrder);
  }


  // Prueba de campo prohibido.
  @Test
  public void shouldReturnBadRequestForUnsupportedField() {
    OrderPatchRequest patch = new OrderPatchRequest();

    patch.setDeliveryCity("Aguascalientes");

    patch.addUnsupportedField("ccNumber","4111111111111111");

    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);

    StepVerifier.create(
      controller.patchOrder(
        "ORDER-1",
        patch,
        authentication))
      .assertNext(response ->
        assertEquals(
          HttpStatus.BAD_REQUEST,
          response.getStatusCode()))
      .verifyComplete();

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

    OrderPatchRequest patch = new OrderPatchRequest();

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
      .assertNext(response ->
        assertEquals(
          HttpStatus.FORBIDDEN,
          response.getStatusCode()))
      .verifyComplete();

    when(repo.findById("ORDER-MISSING")).thenReturn(Mono.empty());

    StepVerifier.create(
      controller.patchOrder(
        "ORDER-MISSING",
        patch,
        authentication))
      .assertNext(response ->
        assertEquals(
          HttpStatus.NOT_FOUND,
          response.getStatusCode()))
      .verifyComplete();


    verify(
      repo,
      never())
      .save(any(TacoOrder.class));
  }
}