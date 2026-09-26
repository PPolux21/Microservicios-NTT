package tacos.web.api.error;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.security.access.AccessDeniedException;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.http.converter.HttpMessageNotReadableException;

import lombok.Getter;

import tacos.web.api.IngredientNotFoundException;
import tacos.web.api.PaymentMethodNotFoundException;
import tacos.web.api.UserNotFoundException;
import tacos.web.api.error.ApiProblem.Violation;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final MediaType PROBLEM_JSON =
      MediaType.parseMediaType("application/problem+json");

  @ExceptionHandler(
      MethodArgumentNotValidException.class)
  public ResponseEntity<ApiProblem> handleValidation(MethodArgumentNotValidException exception,HttpServletRequest request) {


    List<Violation> violations =
        exception.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(error ->
                new Violation(error.getField(),error.getDefaultMessage()))
            .collect(Collectors.toList());


    return problem(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        "Invalid request",
        "The request contains invalid fields.",
        request.getRequestURI(),
        violations);
  }

  @ExceptionHandler(
      HttpMessageNotReadableException.class)
  public ResponseEntity<ApiProblem> handleMalformedJson(HttpMessageNotReadableException exception,HttpServletRequest request) {

    return problem(
        HttpStatus.BAD_REQUEST,
        "MALFORMED_JSON",
        "Malformed JSON",
        "The request body could not be read.",
        request.getRequestURI(),
        Collections.emptyList());
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ApiProblem> handleApiException(ApiException exception,HttpServletRequest request) {

    return problem(
        exception.getStatus(),
        exception.getCode(),
        titleFor(exception.getStatus()),
        exception.getMessage(),
        request.getRequestURI(),
        Collections.emptyList());
  }

  @ExceptionHandler(
      IngredientNotFoundException.class)
  public ResponseEntity<ApiProblem> handleIngredientNotFound(IngredientNotFoundException exception,HttpServletRequest request) {


    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "INGREDIENT_NOT_FOUND",
        "Business rule violation",
        "The order contains an unknown ingredient.",
        request.getRequestURI(),
        Collections.emptyList());
  }

  @ExceptionHandler(
      UserNotFoundException.class)
  public ResponseEntity<ApiProblem> handleUserNotFound(UserNotFoundException exception,HttpServletRequest request) {


    return problem(
        HttpStatus.NOT_FOUND,
        "USER_NOT_FOUND",
        "Resource not found",
        "The requested user does not exist.",
        request.getRequestURI(),
        Collections.emptyList());
  }


  @ExceptionHandler(
      PaymentMethodNotFoundException.class)
  public ResponseEntity<ApiProblem> handlePaymentMethodNotFound(PaymentMethodNotFoundException exception,HttpServletRequest request) {

    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "PAYMENT_METHOD_REQUIRED",
        "Business rule violation",
        "The order cannot be completed without a payment method.",
        request.getRequestURI(),
        Collections.emptyList());
  }


  @ExceptionHandler(
      AccessDeniedException.class)
  public ResponseEntity<ApiProblem> handleAccessDenied(AccessDeniedException exception,HttpServletRequest request) {

    return problem(
        HttpStatus.FORBIDDEN,
        "FORBIDDEN",
        "Forbidden",
        "You are not allowed to perform this operation.",
        request.getRequestURI(),
        Collections.emptyList());
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiProblem> handleResponseStatusException(
          ResponseStatusException exception,HttpServletRequest request) {

    HttpStatus status = exception.getStatus();

    String detail =
        exception.getReason() != null ? exception.getReason() : status.getReasonPhrase();

    String code = 
        status == HttpStatus.CONFLICT ? "CONFLICT" : status.name();

    return problem(
        status,
        code,
        titleFor(status),
        detail,
        request.getRequestURI(),
        Collections.emptyList());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiProblem> handleUnexpected(Exception exception,HttpServletRequest request) {

    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "Internal server error",
        "An unexpected error occurred.",
        request.getRequestURI(),
        Collections.emptyList());
  }


  private ResponseEntity<ApiProblem> problem(
      HttpStatus status,
      String code,
      String title,
      String detail,
      String instance,
      List<Violation> violations) {


    ApiProblem body =
        new ApiProblem(
            "urn:tacocloud:problem:"
                + code.toLowerCase(Locale.ROOT),
            title,
            status.value(),
            detail,
            instance,
            code,
            violations);


    return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(body);
  }


  private String titleFor(
      HttpStatus status) {

    switch (status) {

      case BAD_REQUEST:
        return "Bad request";

      case NOT_FOUND:
        return "Resource not found";

      case FORBIDDEN:
        return "Forbidden";

      case CONFLICT:
        return "Conflict";

      case UNPROCESSABLE_ENTITY:
        return "Business rule violation";

      default:
        return status.getReasonPhrase();
    }
  }

  @Getter
  public static class ApiException
      extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    private final String code;


    public ApiException(HttpStatus status,String code,String detail) {

      super(detail);

      this.status = status;
      this.code = code;
    }


    public static ApiException badRequest(String code,String detail) {

      return new ApiException(HttpStatus.BAD_REQUEST,code,detail);
    }


    public static ApiException notFound(String code,String detail) {

      return new ApiException(HttpStatus.NOT_FOUND,code,detail);
    }


    public static ApiException forbidden(String code,String detail) {

      return new ApiException(HttpStatus.FORBIDDEN,code,detail);
    }


    public static ApiException conflict(String code,String detail) {

      return new ApiException(HttpStatus.CONFLICT,code,detail);
    }


    public static ApiException unprocessable(String code,String detail) {

      return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,code,detail);
    }
  }
}