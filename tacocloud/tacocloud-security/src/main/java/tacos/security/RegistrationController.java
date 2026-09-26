package tacos.security;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.stereotype.Controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import tacos.User;
import tacos.data.UserRepository;

@Controller
@RequestMapping("/register")
public class RegistrationController {

  private UserRepository userRepo;
  private PasswordEncoder passwordEncoder;

  public RegistrationController(
      UserRepository userRepo, PasswordEncoder passwordEncoder) {
    this.userRepo = userRepo;
    this.passwordEncoder = passwordEncoder;
  }

  @GetMapping
  public String registerForm() {
    return "registration";
  }

  @PostMapping
  public Mono<String> processRegistration(RegistrationForm form) {

    return validateUnique(
      form.getUsername(),
      form.getEmail())
      .then(
        Mono.defer(() -> {
          User user = form.toUser(passwordEncoder);
          return userRepo.save(user);
        }))
      .thenReturn("redirect:/login")
      .onErrorMap(DuplicateKeyException.class,error ->duplicateUser());
  }


  private Mono<Void> validateUnique(
      String username,
      String email) {

    Mono<Void> usernameCheck =
        userRepo
            .findByUsername(username)
            .flatMap(existing -> Mono.<Void>error(duplicateUser()))
            .then();

    Mono<Void> emailCheck =
        userRepo
            .findByEmail(email)
            .flatMap(existing -> Mono.<Void>error(duplicateUser()))
            .then();

    return usernameCheck.then(emailCheck);
  }


  private ResponseStatusException duplicateUser() {
    return new ResponseStatusException(HttpStatus.CONFLICT,"Username or email already exists.");
  }
}