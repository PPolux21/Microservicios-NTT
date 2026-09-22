package tacos.web.api;

public class UserNotFoundException
    extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String email;

  public UserNotFoundException(String email) {

    super("No user found for email: " + email);

    this.email = email;
  }

  public String getEmail() {
    return email;
  }
}