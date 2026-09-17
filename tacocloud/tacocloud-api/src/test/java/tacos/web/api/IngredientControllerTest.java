package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.data.IngredientRepository;

public class IngredientControllerTest {
    
  // TC-01: Pruebas con WebTestClient para el método updateIngredient
  // 400 Bad Request test
  @Test 
  public void shouldReturnBadRequestWhenIdsMismatch() {
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Ingredient testIngredient = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);

    WebTestClient testClient = WebTestClient.bindToController(
        new IngredientController(ingredientRepo)).build();

    testClient.put()
        .uri("/api/ingredients/COTO")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(testIngredient)
      .exchange()
      .expectStatus().isBadRequest();
  }

  // 404 Not Found test
  @Test 
  public void shouldReturnNotFoundWhenIngredientDoesNotExist() {
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Ingredient testIngredient = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);

    when(ingredientRepo.findById(any(String.class))).thenReturn(Mono.empty());

    WebTestClient testClient = WebTestClient.bindToController(
        new IngredientController(ingredientRepo)).build();

    testClient.put()
        .uri("/api/ingredients/FLTO")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(testIngredient)
      .exchange()
      .expectStatus().isNotFound();
  }

  // 200 OK test
  @Test 
  public void shouldUpdateIngredientAndReturnOk() {
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Ingredient testIngredient = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);

    when(ingredientRepo.findById(any(String.class))).thenReturn(Mono.just(testIngredient));
    when(ingredientRepo.save(any(Ingredient.class))).thenReturn(Mono.just(testIngredient));

    WebTestClient testClient = WebTestClient.bindToController(
        new IngredientController(ingredientRepo)).build();

    testClient.put()
        .uri("/api/ingredients/FLTO")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(testIngredient)
      .exchange()
      .expectStatus().isOk();
  }

  // StepVerifier test 
  @Test 
  public void shouldUpdateIngredientAndVerify(){
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Ingredient testIngredient = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);

    when(ingredientRepo.findById(any(String.class))).thenReturn(Mono.just(testIngredient));
    when(ingredientRepo.save(any(Ingredient.class))).thenReturn(Mono.just(testIngredient));

    IngredientController controller = new IngredientController(ingredientRepo);

    Mono<ResponseEntity<Ingredient>> resultado = controller.updateIngredient("FLTO", testIngredient);

    StepVerifier.create(resultado)
        .expectNextMatches(response -> response.getStatusCode().is2xxSuccessful())
        .verifyComplete();

    Mockito.verify(ingredientRepo).save(testIngredient);
  }

  // TC-02: Pruebas con WebTestClient para el método deleteIngredient
  // 404 Not Found test
  @Test
  public void shouldReturnNotFoundWhenDeletingNonExistentIngredient() {
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);

    when(ingredientRepo.findById(any(String.class))).thenReturn(Mono.empty());

    WebTestClient testClient = WebTestClient.bindToController(
        new IngredientController(ingredientRepo)).build();

    testClient.delete()
        .uri("/api/ingredients/1234")
      .exchange()
      .expectStatus().isNotFound();

    Mockito.verify(ingredientRepo, Mockito.never())
      .deleteById(any(String.class));
  }

  // 204 No Content test
  @Test
  public void shouldReturnNoContentWhenDeletingExistingIngredient() {
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Ingredient testIngredient = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);

    when(ingredientRepo.findById(any(String.class))).thenReturn(Mono.just(testIngredient));
    when(ingredientRepo.deleteById(any(String.class))).thenReturn(Mono.empty());

    WebTestClient testClient = WebTestClient.bindToController(
        new IngredientController(ingredientRepo)).build();

    testClient.delete()
        .uri("/api/ingredients/FLTO")
      .exchange()
      .expectStatus().isNoContent()
      .expectBody().isEmpty();

      Mockito.verify(ingredientRepo, Mockito.times(1))
        .deleteById("FLTO");
  }

  // Mongo DB test (WIP)
  

  // TC-03: Pruebas para el método postIngredient
  // Prueba con WebTestClient que inspeccione Location
  @Test
  public void shouldReturnLocationWhenCreatingIngredient() {
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Ingredient testIngredient = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);

    when(ingredientRepo.save(any(Ingredient.class))).thenReturn(Mono.just(testIngredient));

    WebTestClient testClient = MockMvcWebTestClient.bindToController(
      new IngredientController(ingredientRepo)).build();

    testClient.post()
      .uri("/api/ingredients")
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(testIngredient)
      .exchange()
      .expectStatus().isCreated()
      .expectHeader().value(
        "Location",
        location -> assertTrue(
          location.endsWith(
            "/api/ingredients/FLTO")));

    Mockito.verify(
      ingredientRepo,
      Mockito.times(1))
      .save(any(Ingredient.class));
  }

  // Prueba que siga Lcation y obtenga 200
  @Test
  public void shouldFollowLocationAndReturnCreatedIngredient() {
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Ingredient testIngredient = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);

    when(ingredientRepo.save(any(Ingredient.class))).thenReturn(Mono.just(testIngredient));
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(testIngredient));

    WebTestClient testClient = MockMvcWebTestClient.bindToController(
            new IngredientController(ingredientRepo)).build();

    EntityExchangeResult<Ingredient> postResult =
      testClient.post()
        .uri("/api/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(testIngredient)
        .exchange()
        .expectStatus().isCreated()
        .expectHeader().exists("Location")
        .expectBody(Ingredient.class)
        .returnResult();

    URI location = postResult.getResponseHeaders().getLocation();

    assertNotNull(location);

    testClient.get()
      .uri(location.getPath())
      .exchange()
      .expectStatus().isOk()
      .expectBody(Ingredient.class)
      .value(ingredient -> {
        assertEquals(
          "FLTO",
          ingredient.getId());

        assertEquals(
          "Flour Tortilla",
          ingredient.getName());
      });

    Mockito.verify(ingredientRepo).save(any(Ingredient.class));
    Mockito.verify(ingredientRepo).findById("FLTO");
  }

  // Prueba de validación 400
  @Test
  public void shouldReturnBadRequestWhenIngredientBodyIsInvalid() {
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    
    WebTestClient testClient = WebTestClient.bindToController(new IngredientController(ingredientRepo)).build();

    String invalidIngredient =
        "{"
        + "\"id\":\"BAD\","
        + "\"name\":\"Invalid Ingredient\","
        + "\"type\":\"INVALID_TYPE\""
        + "}";

    testClient.post()
        .uri("/api/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(invalidIngredient)
        .exchange()
        .expectStatus().isBadRequest();

    Mockito.verify(ingredientRepo,Mockito.never()).save(any(Ingredient.class));
  }
}
