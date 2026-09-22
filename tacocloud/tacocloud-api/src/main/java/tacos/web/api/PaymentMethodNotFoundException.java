package tacos.web.api;

public class PaymentMethodNotFoundException
    extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String userId;

  public PaymentMethodNotFoundException(String userId) {

    super("No payment method found for user: " + userId);

    this.userId = userId;
  }

  public String getUserId() {
    return userId;
  }
}