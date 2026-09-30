package tacos.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import org.springframework.dao.DuplicateKeyException;

import org.springframework.http.HttpStatus;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import tacos.User;
import tacos.data.UserRepository;

public class RegistrationControllerTest {


  /*
   * TC-10
   * Encoder y hash protegido.
   */
  @Test
  public void shouldEncodePasswordWithAlgorithmId() {


    SecurityConfig securityConfig =
        new SecurityConfig();


    PasswordEncoder encoder =
        securityConfig.encoder();


    String rawPassword =
        "secretPassword";


    String encoded =
        encoder.encode(
            rawPassword);


    assertNotEquals(
        rawPassword,
        encoded);


    assertTrue(
        encoded.startsWith(
            "{bcrypt}"));


    assertTrue(
        encoder.matches(
            rawPassword,
            encoded));
  }


  /*
   * TC-10
   * Persistencia reactiva real.
   */
  @Test
  public void shouldPersistBeforeRedirecting() {


    UserRepository userRepo =
        mock(UserRepository.class);


    PasswordEncoder encoder =
        new SecurityConfig()
            .encoder();


    RegistrationController controller =
        new RegistrationController(
            userRepo,
            encoder);


    RegistrationForm form =
        registrationForm();


    when(
        userRepo.findByUsername(
            "jose"))
        .thenReturn(
            Mono.empty());


    when(
        userRepo.findByEmail(
            "jose@test.com"))
        .thenReturn(
            Mono.empty());


    AtomicInteger saveSubscriptions =
        new AtomicInteger();


    when(
        userRepo.save(
            any(User.class)))

        .thenAnswer(invocation -> {

          User user =
              invocation
                  .getArgument(0);


          return Mono.defer(() -> {

            saveSubscriptions
                .incrementAndGet();

            return Mono.just(user);
          });
        });


    Mono<String> result =
        controller
            .processRegistration(
                form);


    /*
     * Construir el publisher
     * no ejecuta todavía el save.
     */
    assertEquals(
        0,
        saveSubscriptions.get());


    StepVerifier
        .create(result)

        .expectNext(
            "redirect:/login")

        .verifyComplete();


    assertEquals(
        1,
        saveSubscriptions.get());


    ArgumentCaptor<User> captor =
        ArgumentCaptor.forClass(
            User.class);


    verify(
        userRepo,
        times(1))
        .save(
            captor.capture());


    User savedUser =
        captor.getValue();


    assertNotEquals(
        "secretPassword",
        savedUser.getPassword());


    assertTrue(
        encoder.matches(
            "secretPassword",
            savedUser.getPassword()));
  }


  /*
   * TC-10
   * Pre-check de username/email
   * y carrera protegida mediante
   * DuplicateKeyException.
   */
  @Test
  public void shouldReturnConflictForDuplicates()
      {


    UserRepository userRepo =
        mock(UserRepository.class);


    PasswordEncoder encoder =
        new SecurityConfig()
            .encoder();


    RegistrationController controller =
        new RegistrationController(
            userRepo,
            encoder);


    RegistrationForm form =
        registrationForm();


    User existing =
        mock(User.class);


    /*
     * Username ya existe.
     */
    when(
        userRepo.findByUsername(
            "jose"))

        .thenReturn(
            Mono.just(existing));


    when(
        userRepo.findByEmail(
            "jose@test.com"))

        .thenReturn(
            Mono.empty());


    StepVerifier
        .create(
            controller
                .processRegistration(
                    form))

        .expectErrorSatisfies(error -> {

          assertTrue(
              error
                  instanceof
                  ResponseStatusException);


          ResponseStatusException exception =
              (ResponseStatusException)
                  error;


          assertEquals(
              HttpStatus.CONFLICT,
              exception.getStatus());
        })

        .verify();


    verify(
        userRepo,
        never())
        .save(
            any(User.class));


    /*
     * Email ya existe.
     */
    reset(userRepo);


    when(
        userRepo.findByUsername(
            "jose"))
        .thenReturn(
            Mono.empty());


    when(
        userRepo.findByEmail(
            "jose@test.com"))
        .thenReturn(
            Mono.just(existing));


    StepVerifier
        .create(
            controller
                .processRegistration(
                    form))

        .expectErrorSatisfies(error -> {

          ResponseStatusException exception =
              (ResponseStatusException)
                  error;


          assertEquals(
              HttpStatus.CONFLICT,
              exception.getStatus());
        })

        .verify();


    verify(
        userRepo,
        never())
        .save(
            any(User.class));


    /*
     * Carrera:
     *
     * las dos consultas no encuentran
     * duplicado, pero Mongo rechaza
     * el save por el índice único.
     */
    reset(userRepo);


    when(
        userRepo.findByUsername(
            "jose"))
        .thenReturn(
            Mono.empty());


    when(
        userRepo.findByEmail(
            "jose@test.com"))
        .thenReturn(
            Mono.empty());


    when(
        userRepo.save(
            any(User.class)))

        .thenReturn(
            Mono.error(
                new DuplicateKeyException(
                    "duplicate key")));


    StepVerifier
        .create(
            controller
                .processRegistration(
                    form))

        .expectErrorSatisfies(error -> {

          assertTrue(
              error
                  instanceof
                  ResponseStatusException);


          ResponseStatusException exception =
              (ResponseStatusException)
                  error;


          assertEquals(
              HttpStatus.CONFLICT,
              exception.getStatus());


          assertFalse(
              exception
                  .getReason()
                  .contains(
                      "duplicate key"));
        })

        .verify();
  }


  private RegistrationForm
      registrationForm() {


    RegistrationForm form =
        new RegistrationForm();


    form.setUsername(
        "jose");

    form.setPassword(
        "secretPassword");

    form.setFullname(
        "Jose Espinoza");

    form.setStreet(
        "Test Street");

    form.setCity(
        "Aguascalientes");

    form.setState(
        "AGS");

    form.setZip(
        "20000");

    form.setPhone(
        "4490000000");

    form.setEmail(
        "jose@test.com");


    return form;
  }
}
