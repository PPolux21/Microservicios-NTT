package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;

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
      .expectStatus().isNoContent();
  }

  // Mongo DB test (WIP)
  

  // TC-03: Pruebas para el método postIngredient
  // Prueba con WebTestClient que inspeccione Location
  @Test
  public void shouldReturnCreatedAndLocationInspection(){
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Ingredient ingredientToSave = new Ingredient("NUIN", "Nuevo Ingrediente", Type.WRAP);

    when(ingredientRepo.save(any(Ingredient.class))).thenReturn(Mono.just(ingredientToSave));

    WebTestClient testClient = WebTestClient.bindToController(
        new IngredientController(ingredientRepo)).build();

    testClient.post()
        .uri("/api/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(ingredientToSave)
      .exchange()
      .expectStatus().isCreated()
      .expectHeader().valueMatches("Location", ".*?/api/ingredients/NUIN$");
  }

  // Prueba que siga Lcation y obtenga 200

  // Prueba de validación 400

}
