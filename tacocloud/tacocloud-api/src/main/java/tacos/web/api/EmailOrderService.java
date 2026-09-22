package tacos.web.api;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.PaymentMethod;
import tacos.Taco;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.web.api.EmailOrder.EmailTaco;

@Service
public class EmailOrderService {

  private UserRepository userRepo;
  private IngredientRepository ingredientRepo;
  private PaymentMethodRepository paymentMethodRepo;

  public EmailOrderService(UserRepository userRepo, IngredientRepository ingredientRepo,
    PaymentMethodRepository paymentMethodRepo) {
    this.userRepo = userRepo;
    this.ingredientRepo = ingredientRepo;
    this.paymentMethodRepo = paymentMethodRepo;
  }

  public Mono<TacoOrder> convertEmailOrderToDomainOrder(Mono<EmailOrder> emailOrder) {

    return emailOrder.flatMap(eOrder ->

        userRepo.findByEmail(eOrder.getEmail())
            .switchIfEmpty(
              Mono.error(new UserNotFoundException(eOrder.getEmail())))
            .flatMap(user ->
              paymentMethodRepo
                .findByUserId(user.getId())
                .switchIfEmpty(
                  Mono.error(new PaymentMethodNotFoundException(user.getId())))
                .flatMap(paymentMethod ->
                  convertTacos(eOrder.getTacos())
                    .map(tacos -> buildOrder(user,paymentMethod,tacos)))));
}

  private Mono<List<Taco>> convertTacos(List<EmailTaco> emailTacos) {

    return Flux.fromIterable(emailTacos)
        .concatMap(this::convertTaco)
        .collectList();
  }

  private Mono<Taco> convertTaco(EmailTaco emailTaco) {

    return Flux
        .fromIterable(emailTaco.getIngredients())
        .concatMap(ingredientId ->
          ingredientRepo
            .findById(ingredientId)
            .switchIfEmpty(Mono.error(new IngredientNotFoundException(ingredientId))))
        .collectList()
        .map(ingredients -> {
          Taco taco = new Taco();
          taco.setName(emailTaco.getName());
          taco.setIngredients(ingredients);

          return taco;
        });
  }

  private TacoOrder buildOrder(User user,PaymentMethod paymentMethod,List<Taco> tacos) {

    TacoOrder order = new TacoOrder();

    order.setUser(user);
    order.setCcNumber(paymentMethod.getCcNumber());
    order.setCcCVV(paymentMethod.getCcCVV());
    order.setCcExpiration(paymentMethod.getCcExpiration());
    order.setDeliveryName(user.getFullname());
    order.setDeliveryStreet(user.getStreet());
    order.setDeliveryCity(user.getCity());
    order.setDeliveryState(user.getState());
    order.setDeliveryZip(user.getZip());
    order.setPlacedAt(new Date());

    for (Taco taco : tacos)
      order.addTaco(taco);
    
    return order;
  }
}