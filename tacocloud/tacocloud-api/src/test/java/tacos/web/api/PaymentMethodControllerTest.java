package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import org.springframework.security.core.Authentication;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import tacos.PaymentMethod;
import tacos.User;

import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;

import tacos.web.api.dto.ApiDtos.PaymentTokenizeRequest;
import tacos.web.api.payment.FakePaymentGateway;
import tacos.web.api.payment.PaymentGateway;
import tacos.web.api.payment.PaymentMethodController;


public class PaymentMethodControllerTest {

  @Test
  public void shouldTokenizeSyntheticCardSafely() {

    PaymentGateway gateway =
        new FakePaymentGateway();


    PaymentMethodRepository paymentMethodRepo = 
        mock(PaymentMethodRepository.class);


    UserRepository userRepo = mock(UserRepository.class);


    PaymentMethodController controller = 
        new PaymentMethodController(
            gateway,
            paymentMethodRepo,
            userRepo);


    Authentication authentication = mock(Authentication.class);

    when(authentication.getName()).thenReturn("jose");

    User user = mock(User.class);

    when(user.getId()).thenReturn("USER-1");

    when(userRepo.findByUsername("jose"))
        .thenReturn(Mono.just(user));

    PaymentTokenizeRequest request = new PaymentTokenizeRequest();

    request.setCardNumber("4111111111111111");
    request.setExpiration("12/30");
    request.setCvv("123");

    when(
        paymentMethodRepo.save(any(PaymentMethod.class)))

        .thenAnswer(invocation -> {
          PaymentMethod method = invocation.getArgument(0);
          method.setId("PAYMENT-1");

          return Mono.just(method);
        });

    StepVerifier.create(
        controller.tokenize(request,authentication))

        .assertNext(response -> {

          assertEquals("PAYMENT-1",response.getId());
          assertEquals("VISA",response.getBrand());
          assertEquals("1111",response.getLast4());
        })
        .verifyComplete();

    ArgumentCaptor<PaymentMethod>
        captor = ArgumentCaptor.forClass(PaymentMethod.class);

    verify(paymentMethodRepo).save(captor.capture());

    PaymentMethod persisted = captor.getValue();

    assertFalse(persisted.getPaymentToken().contains("4111111111111111"));
    assertEquals("VISA",persisted.getBrand());
    assertEquals("1111",persisted.getLast4());
  }
}