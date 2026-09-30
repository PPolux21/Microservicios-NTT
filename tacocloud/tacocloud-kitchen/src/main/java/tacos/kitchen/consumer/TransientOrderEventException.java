package tacos.kitchen.consumer;

public class TransientOrderEventException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public TransientOrderEventException(String message,Throwable cause) {
    super(message,cause);
  }
}
