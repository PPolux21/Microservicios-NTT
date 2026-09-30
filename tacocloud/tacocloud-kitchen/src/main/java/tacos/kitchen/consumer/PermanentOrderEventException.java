package tacos.kitchen.consumer;

public class PermanentOrderEventException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String code;

  public PermanentOrderEventException(String code,String message) {
    super(message);
    this.code = code;
  }

  public PermanentOrderEventException(String code,String message,
      Throwable cause) {
    super(message,cause);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
