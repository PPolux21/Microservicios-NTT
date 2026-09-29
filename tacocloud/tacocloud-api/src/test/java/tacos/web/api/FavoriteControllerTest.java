package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import tacos.web.api.dto.ApiDtos.FavoriteQuery;

public class FavoriteControllerTest {

  @Test
  public void shouldIgnoreBodyUserIdBecauseOwnershipComesFromAuthentication() {
    FavoriteService service = Mockito.mock(FavoriteService.class);
    when(service.addFavorite(
        Mockito.eq("TACO-1"),nullable(Authentication.class)))
        .thenReturn(Mono.empty());
    FavoriteController controller = new FavoriteController(service);

    WebTestClient.bindToController(controller).build()
        .put().uri("/api/users/me/favorites/TACO-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"userId\":\"another-user\"}")
        .exchange()
        .expectStatus().isNoContent();

    verify(service).addFavorite("TACO-1",null);
    assertFalse(Arrays.stream(FavoriteQuery.class.getDeclaredFields())
        .anyMatch(field -> "userId".equals(field.getName())));
  }
}
