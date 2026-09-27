package tacos.web.api.payment;

import reactor.core.publisher.Mono;


public interface PaymentGateway {

  Mono<TokenizedPayment> tokenize(CardData cardData);


  public static final class CardData {

    private final String cardNumber;

    private final String expiration;

    private final String cvv;


    public CardData(
        String cardNumber,
        String expiration,
        String cvv) {

      this.cardNumber = cardNumber;

      this.expiration = expiration;

      this.cvv = cvv;
    }


    public String getCardNumber() {
      return cardNumber;
    }


    public String getExpiration() {
      return expiration;
    }


    public String getCvv() {
      return cvv;
    }


    @Override
    public String toString() {
      return "CardData[REDACTED]";
    }
  }


  public static final class TokenizedPayment {

    private final String token;

    private final String brand;

    private final String last4;

    private final String expiration;


    public TokenizedPayment(
        String token,
        String brand,
        String last4,
        String expiration) {

      this.token = token;

      this.brand = brand;

      this.last4 = last4;

      this.expiration = expiration;
    }


    public String getToken() {
      return token;
    }


    public String getBrand() {
      return brand;
    }


    public String getLast4() {
      return last4;
    }


    public String getExpiration() {
      return expiration;
    }

    @Override
    public String toString() {

      return "TokenizedPayment[brand="
          + brand
          + ", last4="
          + last4
          + "]";
    }
  }
}