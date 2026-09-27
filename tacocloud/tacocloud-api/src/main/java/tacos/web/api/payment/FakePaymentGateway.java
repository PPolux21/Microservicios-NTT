package tacos.web.api.payment;

import java.util.UUID;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;


@Component
public class FakePaymentGateway
    implements PaymentGateway {


  @Override
  public Mono<TokenizedPayment> tokenize(
      CardData cardData) {

    return Mono.fromSupplier(() -> {

      String cardNumber = cardData.getCardNumber();

      if (cardNumber == null || cardNumber.length() < 4) {
        throw new IllegalArgumentException("Invalid synthetic card data");
      }

      String last4 = cardNumber.substring(cardNumber.length() - 4);

      String brand = detectBrand(cardNumber);

      String token =
        "tok_fake_"
        + UUID.randomUUID()
          .toString()
          .replace("-", "");

      return new TokenizedPayment(
        token,
        brand,
        last4,
        cardData.getExpiration());
    });
  }


  private String detectBrand(
      String cardNumber) {

    if (cardNumber.startsWith("4")) {
      return "VISA";
    }

    if (cardNumber.startsWith("5")) {
      return "MASTERCARD";
    }

    return "UNKNOWN";
  }
}