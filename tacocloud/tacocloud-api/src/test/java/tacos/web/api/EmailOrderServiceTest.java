package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.PaymentMethod;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.web.api.EmailOrder.EmailTaco;

@ExtendWith(MockitoExtension.class)
public class EmailOrderServiceTest {

  @Mock
  private UserRepository userRepo;

  @Mock
  private IngredientRepository ingredientRepo;

  @Mock
  private PaymentMethodRepository paymentMethodRepo;

  @InjectMocks
  private EmailOrderService service;

  // TC-06
  // Caso feliz con varios tacos.
  @Test
  public void shouldConvertEmailOrderWithMultipleTacos() {

    EmailOrder emailOrder = mock(EmailOrder.class);
    EmailTaco tacoOne = mock(EmailTaco.class);
    EmailTaco tacoTwo = mock(EmailTaco.class);
    User user = mock(User.class);
    PaymentMethod paymentMethod = mock(PaymentMethod.class);
    Ingredient flour = mock(Ingredient.class);
    Ingredient beef = mock(Ingredient.class);
    Ingredient corn = mock(Ingredient.class);
    Ingredient tomato = mock(Ingredient.class);

    when(emailOrder.getEmail()).thenReturn("jose@test.com");
    when(emailOrder.getTacos()).thenReturn(Arrays.asList(tacoOne,tacoTwo));
    when(tacoOne.getName()).thenReturn("Taco One");
    when(tacoOne.getIngredients()).thenReturn(Arrays.asList("FLTO","GRBF"));
    when(tacoTwo.getName()).thenReturn("Taco Two");
    when(tacoTwo.getIngredients()).thenReturn(Arrays.asList("COTO","TMTO"));

    when(user.getId()).thenReturn("USER-1");
    when(userRepo.findByEmail("jose@test.com")).thenReturn(Mono.just(user));
    when(paymentMethodRepo.findByUserId("USER-1")).thenReturn(Mono.just(paymentMethod));
    when(paymentMethod.getPaymentToken()).thenReturn("tok_fake_test");

    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(flour));
    when(ingredientRepo.findById("GRBF")).thenReturn(Mono.just(beef));
    when(ingredientRepo.findById("COTO")).thenReturn(Mono.just(corn));
    when(ingredientRepo.findById("TMTO")).thenReturn(Mono.just(tomato));

    StepVerifier.create(service.convertEmailOrderToDomainOrder(Mono.just(emailOrder)))
        .assertNext(order -> {
          assertSame(user,order.getUser());
          assertEquals(2,order.getTacos().size());

          assertEquals("Taco One",order.getTacos().get(0).getName());
          assertEquals(2,order.getTacos().get(0).getIngredients().size());
          assertSame(flour,order.getTacos().get(0).getIngredients().get(0));
          assertSame(beef,order.getTacos().get(0).getIngredients().get(1));

          assertEquals("Taco Two",order.getTacos().get(1).getName());
          assertEquals(2,order.getTacos().get(1).getIngredients().size());
          assertSame(corn,order.getTacos().get(1).getIngredients().get(0));
          assertSame(tomato,order.getTacos().get(1).getIngredients().get(1));
        }).verifyComplete();
  }


  // TC-06
  // Ingrediente inexistente.
  @Test
  public void shouldFailWhenIngredientDoesNotExist() {

    EmailOrder emailOrder = mock(EmailOrder.class);
    EmailTaco emailTaco = mock(EmailTaco.class);
    User user = mock(User.class);
    PaymentMethod paymentMethod = mock(PaymentMethod.class);
    Ingredient flour = mock(Ingredient.class);

    when(emailOrder.getEmail()).thenReturn("jose@test.com");
    when(emailOrder.getTacos()).thenReturn(Arrays.asList(emailTaco));
    when(emailTaco.getIngredients()).thenReturn(Arrays.asList("FLTO","UNKNOWN"));
    when(user.getId()).thenReturn("USER-1");
    when(userRepo.findByEmail("jose@test.com")).thenReturn(Mono.just(user));
    when(paymentMethodRepo.findByUserId("USER-1")).thenReturn(Mono.just(paymentMethod));
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(flour));
    when(ingredientRepo.findById("UNKNOWN")).thenReturn(Mono.empty());
    when(paymentMethod.getPaymentToken()).thenReturn("tok_fake_test");

    StepVerifier.create(
        service.convertEmailOrderToDomainOrder(
          Mono.just(emailOrder)))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof IngredientNotFoundException);
          IngredientNotFoundException exception = (IngredientNotFoundException) error;
          assertEquals("UNKNOWN",exception.getIngredientId());
        }).verify();
  }


  // TC-06
  // Usuario y método de pago ausentes.
  @Test
  public void shouldFailWhenUserOrPaymentMethodIsMissing() {

    EmailOrder missingUserOrder = mock(EmailOrder.class);

    when(missingUserOrder.getEmail()).thenReturn("missing@test.com");
    when(userRepo.findByEmail("missing@test.com")).thenReturn(Mono.empty());

    StepVerifier.create(
        service.convertEmailOrderToDomainOrder(
          Mono.just(missingUserOrder)))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof UserNotFoundException);
          UserNotFoundException exception = (UserNotFoundException) error;
          assertEquals("missing@test.com",exception.getEmail());
        }).verify();

    EmailOrder missingPaymentOrder = mock(EmailOrder.class);
    User user = mock(User.class);

    when(missingPaymentOrder.getEmail()).thenReturn("jose@test.com");
    when(user.getId()).thenReturn("USER-1");
    when(userRepo.findByEmail("jose@test.com")).thenReturn(Mono.just(user));
    when(paymentMethodRepo.findByUserId("USER-1")).thenReturn(Mono.empty());

    StepVerifier.create(
        service.convertEmailOrderToDomainOrder(
          Mono.just(missingPaymentOrder)))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof PaymentMethodNotFoundException);
          PaymentMethodNotFoundException exception = (PaymentMethodNotFoundException) error;
          assertEquals("USER-1",exception.getUserId());
        }).verify();
  }
}