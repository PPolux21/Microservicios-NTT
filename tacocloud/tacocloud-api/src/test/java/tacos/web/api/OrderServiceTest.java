package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.Authentication;
import org.springframework.data.domain.Pageable;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;

public class OrderServiceTest {

  private OrderRepository repo;
  private OrderMessagingService orderMessages;
  private EmailOrderService emailOrderService;

  private OrderService service;
  private UserRepository userRepo;

  @BeforeEach
  public void setup() {

    repo = Mockito.mock(OrderRepository.class);

    orderMessages = Mockito.mock(OrderMessagingService.class);

    emailOrderService = Mockito.mock(EmailOrderService.class);
    
    userRepo = Mockito.mock(UserRepository.class);

    service = new OrderService(repo,orderMessages,emailOrderService,userRepo);
  }


  /*
   * TC-07
   * Una conversión produce exactamente
   * un guardado y una publicación.
   */
  @Test
  public void shouldSaveAndPublishExactlyOnce() {

    Mono<EmailOrder> emailOrder = 
        Mono.just(Mockito.mock(EmailOrder.class));

    TacoOrder convertedOrder = new TacoOrder();

    TacoOrder savedOrder = new TacoOrder();

    when(emailOrderService.convertEmailOrderToDomainOrder(emailOrder))
        .thenReturn(Mono.just(convertedOrder));

    when(repo.save(convertedOrder)).thenReturn(Mono.just(savedOrder));

    StepVerifier.create(service.createOrderFromEmail(emailOrder))
        .assertNext(order -> assertSame(savedOrder,order))
        .verifyComplete();

    verify(emailOrderService,times(1))
      .convertEmailOrderToDomainOrder(emailOrder);

    verify(repo,times(1)).save(convertedOrder);

    verify(orderMessages,times(1)).sendOrder(savedOrder);
  }


  /*
   * TC-07
   * Error de conversión o guardado no debe provocar publicación.
   */
  @Test
  public void shouldNotPublishWhenConversionOrSaveFails() {

    Mono<EmailOrder> emailOrder = 
      Mono.just(Mockito.mock(EmailOrder.class));


    // Falla la conversión.
    when(emailOrderService.convertEmailOrderToDomainOrder(emailOrder))
      .thenReturn(Mono.error(new RuntimeException("Conversion error")));

    StepVerifier.create(service.createOrderFromEmail(emailOrder))
        .expectError(RuntimeException.class)
        .verify();

    verify(repo,never()).save(any(TacoOrder.class));

    verify(orderMessages,never()).sendOrder(any(TacoOrder.class));


    // La conversión funciona pero falla el guardado.
    reset(repo,orderMessages,emailOrderService);

    TacoOrder convertedOrder = new TacoOrder();

    when(emailOrderService.convertEmailOrderToDomainOrder(emailOrder))
        .thenReturn(Mono.just(convertedOrder));

    when(repo.save(convertedOrder))
      .thenReturn(Mono.error(new RuntimeException("Save error")));

    StepVerifier.create(service.createOrderFromEmail(emailOrder))
        .expectError(RuntimeException.class)
        .verify();

    verify(repo,times(1)).save(convertedOrder);

    verify(orderMessages,never()).sendOrder(any(TacoOrder.class));
  }


  /*
   * TC-07
   * Publisher frío:
   * la conversión no debe
   * ejecutarse dos veces.
   */
  @Test
  public void shouldNotDuplicateColdPublisherExecution() {

    Mono<EmailOrder> emailOrder =
        Mono.just(Mockito.mock(EmailOrder.class));

    TacoOrder convertedOrder = new TacoOrder();

    TacoOrder savedOrder = new TacoOrder();

    AtomicInteger conversionSubscriptions = new AtomicInteger();

    AtomicInteger saveSubscriptions = new AtomicInteger();

    Mono<TacoOrder> coldConversion =
        Mono.defer(() -> {
          conversionSubscriptions.incrementAndGet();
          return Mono.just(convertedOrder);
        });

    when(emailOrderService.convertEmailOrderToDomainOrder(emailOrder))
        .thenReturn(coldConversion);

    when(repo.save(convertedOrder))
        .thenAnswer(invocation ->
            Mono.defer(() -> {
              saveSubscriptions.incrementAndGet();
              return Mono.just(savedOrder);
            }));

    StepVerifier.create(service.createOrderFromEmail(emailOrder))
        .expectNext(savedOrder)
        .verifyComplete();

    assertEquals(1,conversionSubscriptions.get());

    assertEquals(1,saveSubscriptions.get());

    verify(orderMessages,times(1)).sendOrder(savedOrder);
  }

  @Test
  public void shouldEnforceOrderOwnershipInService() {

    User jose = Mockito.mock(User.class);

    User ana = Mockito.mock(User.class);

    when(jose.getUsername()).thenReturn("jose");

    when(ana.getUsername()).thenReturn("ana");


    TacoOrder joseOrder = new TacoOrder();

    joseOrder.setUser(jose);

    TacoOrder anaOrder = new TacoOrder();

    anaOrder.setUser(ana);


    Authentication userAuth = Mockito.mock(Authentication.class);

    when(userAuth.getName())
        .thenReturn("jose");

    doReturn(
      Collections.singletonList(
          new SimpleGrantedAuthority(
              "ROLE_ADMIN")))
      .when(userAuth)
      .getAuthorities();

    when(
        userRepo.findByUsername("jose"))
        .thenReturn(Mono.just(jose));


    when(
        repo.findByUserOrderByPlacedAtDesc(
            Mockito.eq(jose),
            Mockito.any(Pageable.class)))

        .thenReturn(Flux.just(joseOrder));

    StepVerifier.create(
        service.findOrdersFor(userAuth))
        .expectNext(joseOrder)
        .verifyComplete();

    assertFalse(
        service.canAccessOrder(anaOrder,userAuth));

    Authentication adminAuth = Mockito.mock(Authentication.class);

    doReturn(
      Collections.singletonList(
          new SimpleGrantedAuthority(
              "ROLE_ADMIN")))
      .when(adminAuth)
      .getAuthorities();

    when(repo.findAll())
        .thenReturn(Flux.just(joseOrder,anaOrder));

    StepVerifier.create(service.findOrdersFor(adminAuth))
        .expectNext(joseOrder,anaOrder)
        .verifyComplete();

    assertTrue(service.canAccessOrder(anaOrder,adminAuth));
  }
}