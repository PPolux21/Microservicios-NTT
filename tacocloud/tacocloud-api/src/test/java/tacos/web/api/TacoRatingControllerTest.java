package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import reactor.core.publisher.Mono;
import tacos.web.api.dto.ApiDtos.RatingRequest;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

public class TacoRatingControllerTest {

  @Test
  public void shouldAcceptOnlyScoreAndIgnoreClientUserId() {
    TacoRatingService service = Mockito.mock(TacoRatingService.class);
    when(service.rate(Mockito.eq("TACO-1"),Mockito.eq(4),
        nullable(Authentication.class))).thenReturn(Mono.empty());

    client(service).put().uri("/api/tacos/TACO-1/rating")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"score\":4,\"userId\":\"another-user\"}")
        .exchange()
        .expectStatus().isNoContent();

    verify(service).rate("TACO-1",4,null);
    assertFalse(Arrays.stream(RatingRequest.class.getDeclaredFields())
        .anyMatch(field -> "userId".equals(field.getName())));
  }

  @Test
  public void shouldRejectScoresOutsideOneToFive() {
    TacoRatingService service = Mockito.mock(TacoRatingService.class);

    client(service).put().uri("/api/tacos/TACO-1/rating")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"score\":0}")
        .exchange().expectStatus().isBadRequest();
    client(service).put().uri("/api/tacos/TACO-1/rating")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"score\":6}")
        .exchange().expectStatus().isBadRequest();

    verify(service,never()).rate(
        Mockito.anyString(),Mockito.anyInt(),Mockito.any());
  }

  @Test
  public void shouldValidateTopLimit() {
    TacoRatingService service = Mockito.mock(TacoRatingService.class);
    when(service.top(10)).thenReturn(Mono.just(Collections.emptyList()));

    client(service).get().uri("/api/tacos/top?limit=10")
        .exchange().expectStatus().isOk();
    client(service).get().uri("/api/tacos/top?limit=0")
        .exchange().expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("INVALID_LIMIT");
    client(service).get().uri("/api/tacos/top?limit=51")
        .exchange().expectStatus().isBadRequest();
  }

  private WebTestClient client(TacoRatingService service) {
    TacoRatingController controller = new TacoRatingController(service,50);
    return WebTestClient.bindToController(controller)
        .controllerAdvice(new TestApiExceptionHandler())
        .build();
  }

  @RestControllerAdvice
  private static class TestApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String,String>> handle(ApiException error) {
      return ResponseEntity.status(error.getStatus())
          .body(Collections.singletonMap("code",error.getCode()));
    }
  }
}
