package tacos.web.api.payment;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;

import org.springframework.security.core.Authentication;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import tacos.PaymentMethod;

import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;

import tacos.web.api.dto.ApiDtos.PaymentMethodResponse;
import tacos.web.api.dto.ApiDtos.PaymentTokenizeRequest;

import tacos.web.api.payment.PaymentGateway.CardData;


@RestController
@RequestMapping(path = "/api/payment-methods",produces = "application/json")
public class PaymentMethodController {

  private final PaymentGateway gateway;

  private final PaymentMethodRepository paymentMethodRepo;

  private final UserRepository userRepo;


  public PaymentMethodController(
      PaymentGateway gateway,
      PaymentMethodRepository paymentMethodRepo,
      UserRepository userRepo) {

    this.gateway = gateway;

    this.paymentMethodRepo = paymentMethodRepo;

    this.userRepo = userRepo;
  }


  @PostMapping(path = "/tokenize",consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<PaymentMethodResponse> tokenize(
      @Valid
      @RequestBody
      PaymentTokenizeRequest request,

      Authentication authentication) {


    if (authentication == null) {
      return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required"));
    }


    return userRepo
      .findByUsername(authentication.getName())
      .switchIfEmpty(
        Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Authenticated user not found")))
      .flatMap(user -> {

        CardData cardData =
          new CardData(
            request.getCardNumber(),
            request.getExpiration(),
            request.getCvv());


        return gateway
          .tokenize(cardData)
          .flatMap(tokenized -> {
            PaymentMethod method = new PaymentMethod(user);
            method.setId(user.getId());
            method.setPaymentToken(tokenized.getToken());
            method.setBrand(tokenized.getBrand());
            method.setLast4(tokenized.getLast4());
            method.setExpiration(tokenized.getExpiration());

            return paymentMethodRepo.save(method);
          });
      })

      .map(method ->
        new PaymentMethodResponse(
          method.getId(),
          method.getBrand(),
          method.getLast4()));
  }
}