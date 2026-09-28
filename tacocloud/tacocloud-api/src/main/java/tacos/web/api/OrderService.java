package tacos.web.api;

import java.util.Date;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.User;
import tacos.TacoOrder;
import tacos.PaymentMethod;
import tacos.web.api.mapper.ApiMapper;
import tacos.web.api.mapper.ApiMapper.OrderCreateCommand;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;

@Service
public class OrderService {

  private OrderRepository repo;
  private OrderMessagingService orderMessages;
  private EmailOrderService emailOrderService;
  private UserRepository userRepo;
  private PaymentMethodRepository paymentMethodRepo;

  public OrderService(
      OrderRepository repo,
      OrderMessagingService orderMessages,
      EmailOrderService emailOrderService,
      UserRepository userRepo,
      PaymentMethodRepository paymentMethodRepo) {

    this.repo = repo;
    this.orderMessages = orderMessages;
    this.emailOrderService = emailOrderService;
    this.userRepo = userRepo;
    this.paymentMethodRepo = paymentMethodRepo;
  }

  public Mono<TacoOrder> createOrder(OrderCreateCommand command, 
      Authentication authentication) {

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
            .flatMap(paymentMethod -> {
              TacoOrder order = ApiMapper.toEntity(command);
              order.setUser(user);
              order.setPlacedAt(new Date());

              return repo
                .save(order)
                .flatMap(savedOrder ->
                  Mono.fromRunnable(() -> orderMessages.sendOrder(savedOrder))
                  .thenReturn(savedOrder));
            }));
  }

  /*
   * TC-07
   * Se conserva el flujo de órdenes
   * recibidas por correo.
   */
  public Mono<TacoOrder> createOrderFromEmail(
      Mono<EmailOrder> emailOrder) {

    return emailOrderService
        .convertEmailOrderToDomainOrder(emailOrder)

        .flatMap(repo::save)

        .flatMap(savedOrder ->
            Mono.fromRunnable(() ->
                orderMessages.sendOrder(savedOrder))
                .thenReturn(savedOrder));
  }

  public Flux<TacoOrder> findOrdersFor(Authentication authentication) {

    if (authentication == null) {
      return Flux.empty();
    }

    if (hasRole(authentication,"ROLE_ADMIN")) {
      return repo.findAll();
    }

    return userRepo
        .findByUsername(
            authentication.getName())
        .flatMapMany(user ->
            repo.findByUserOrderByPlacedAtDesc(user,Pageable.unpaged()));
  }

  public boolean canAccessOrder(TacoOrder order,
    Authentication authentication) {

    if (authentication == null) {
      return false;
    }


    if (hasRole(authentication,"ROLE_ADMIN")) {
      return true;
    }

    return order.getUser() != null
        && order.getUser()
            .getUsername() != null
        && order.getUser()
            .getUsername()
            .equals(authentication.getName());
  }

  private boolean hasRole(Authentication authentication,
      String role) {

    return authentication
        .getAuthorities()
        .stream()
        .anyMatch(authority ->
            role.equals(authority.getAuthority()));
  }
}
