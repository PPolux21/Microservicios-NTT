package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;

import tacos.data.OrderRepository;
import tacos.web.api.error.ApiExceptionHandler;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.error.ApiProblem;

public class ApiExceptionHandlerTest {

  private static final MediaType PROBLEM_JSON =
      MediaType.parseMediaType(
          "application/problem+json");


  /*
   * TC-09
   * Validación con varios campos.
   */
  @Test
  public void shouldReturnMultipleValidationViolations() {

    OrderRepository repo =
        Mockito.mock(
            OrderRepository.class);

    OrderService orderService =
        Mockito.mock(
            OrderService.class);

    OrderWorkflowService workflowService =
        Mockito.mock(OrderWorkflowService.class);


    OrderApiController controller =
        new OrderApiController(
            repo,
            orderService,
            workflowService);


    WebTestClient client =
        MockMvcWebTestClient
            .bindToController(controller)
            .controllerAdvice(
                new ApiExceptionHandler())
            .build();


    String invalidOrder =
        "{"
        + "\"deliveryName\":\"\","
        + "\"deliveryStreet\":\"\","
        + "\"deliveryCity\":\"\","
        + "\"deliveryState\":\"X\","
        + "\"deliveryZip\":\"!\","
        + "\"items\":[]"
        + "}";


    ApiProblem problem =
        client.post()
            .uri("/api/orders")
            .contentType(
                MediaType.APPLICATION_JSON)
            .bodyValue(invalidOrder)

            .exchange()

            .expectStatus()
            .isBadRequest()

            .expectHeader()
            .contentTypeCompatibleWith(
                PROBLEM_JSON)

            .expectBody(
                ApiProblem.class)

            .returnResult()
            .getResponseBody();


    assertEquals(
        400,
        problem.getStatus());

    assertEquals(
        "VALIDATION_ERROR",
        problem.getCode());

    assertEquals(
        "/api/orders",
        problem.getInstance());


    Set<String> fields =
        problem.getViolations()
            .stream()
            .map(
                ApiProblem.Violation::getField)
            .collect(
                Collectors.toSet());


    assertTrue(
        fields.contains(
            "deliveryName"));

    assertTrue(
        fields.contains(
            "deliveryStreet"));

    assertTrue(
        fields.contains(
            "deliveryCity"));

    assertTrue(
        fields.contains(
            "deliveryState"));

    assertTrue(
        fields.contains(
            "deliveryZip"));

    assertTrue(
        fields.contains(
            "items"));


    /*
     * Validación ocurre antes
     * de cualquier efecto.
     */
    Mockito.verify(
        orderService,
        Mockito.never())
        .createOrder(
            Mockito.any(),
            Mockito.any());
  }


  /*
   * TC-09
   * 404 / 409 / 422 no se
   * convierten en 500.
   */
  @Test
  public void shouldMapFunctionalExceptionsToCorrectStatus() {

    WebTestClient client =
        MockMvcWebTestClient
            .bindToController(
                new TestErrorController())
            .controllerAdvice(
                new ApiExceptionHandler())
            .build();


    client.get()
        .uri("/test/not-found")

        .exchange()

        .expectStatus()
        .isNotFound()

        .expectBody()

        .jsonPath("$.code")
        .isEqualTo(
            "RESOURCE_NOT_FOUND");


    client.get()
        .uri("/test/conflict")

        .exchange()

        .expectStatus()
        .isEqualTo(409)

        .expectBody()

        .jsonPath("$.code")
        .isEqualTo(
            "ORDER_CONFLICT");


    client.get()
        .uri("/test/business")

        .exchange()

        .expectStatus()
        .isEqualTo(422)

        .expectBody()

        .jsonPath("$.code")
        .isEqualTo(
            "BUSINESS_RULE_VIOLATION");
  }


  /*
   * TC-09
   * No filtrar stacktrace,
   * nombre de driver o mensaje
   * interno.
   */
  @Test
  public void shouldHideInternalExceptionDetails() {

    WebTestClient client =
        MockMvcWebTestClient
            .bindToController(
                new TestErrorController())
            .controllerAdvice(
                new ApiExceptionHandler())
            .build();


    ApiProblem problem =
        client.get()
            .uri("/test/internal")

            .exchange()

            .expectStatus()
            .is5xxServerError()

            .expectHeader()
            .contentTypeCompatibleWith(
                PROBLEM_JSON)

            .expectBody(
                ApiProblem.class)

            .returnResult()
            .getResponseBody();


    assertEquals(
        "INTERNAL_ERROR",
        problem.getCode());

    assertEquals(
        500,
        problem.getStatus());


    String response =
        problem.toString();


    assertFalse(
        response.contains(
            "MongoTimeoutException"));

    assertFalse(
        response.contains(
            "com.mongodb"));

    assertFalse(
        response.toLowerCase()
            .contains("stacktrace"));
  }


  @RestController
  static class TestErrorController {

    @GetMapping("/test/not-found")
    public void notFound() {

      throw ApiException.notFound(
          "RESOURCE_NOT_FOUND",
          "Resource does not exist.");
    }


    @GetMapping("/test/conflict")
    public void conflict() {

      throw ApiException.conflict(
          "ORDER_CONFLICT",
          "Resource is in a conflicting state.");
    }


    @GetMapping("/test/business")
    public void business() {

      throw ApiException.unprocessable(
          "BUSINESS_RULE_VIOLATION",
          "Business rule was not satisfied.");
    }


    @GetMapping("/test/internal")
    public void internal() {

      throw new RuntimeException(
          "MongoTimeoutException: "
          + "com.mongodb.driver failed");
    }
  }
}
