package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Collections;
import java.util.EnumSet;

import javax.validation.Validation;
import javax.validation.Validator;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Ingredient.Allergen;
import tacos.Ingredient.DietaryTag;
import tacos.Ingredient.SpiceLevel;
import tacos.data.IngredientRepository;
import tacos.web.api.dto.ApiDtos.IngredientCatalogUpdateRequest;
import tacos.web.api.dto.ApiDtos.IngredientRequest;
import tacos.web.api.dto.ApiDtos.IngredientResponse;
import tacos.web.api.dto.ApiDtos.StockAdjustmentRequest;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

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

    IngredientRequest request = new IngredientRequest();

    request.setId("FLTO");
    request.setName("Flour Tortilla");
    request.setType(Type.WRAP);

    Mono<ResponseEntity<IngredientResponse>> resultado = controller.updateIngredient("FLTO",request);

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

  @Test
  public void shouldExposePriceAndAvailabilityWithoutOperationalMetadata()
      throws Exception {

    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Ingredient ingredient = catalogIngredient("1.235",true,10,2,4L);

    when(ingredientRepo.findAll()).thenReturn(Flux.just(ingredient));

    ObjectMapper mapper = new ObjectMapper();

    StepVerifier.create(new IngredientController(ingredientRepo).allIngredients())
        .assertNext(response -> {
          assertEquals(new BigDecimal("1.24"),response.getUnitPrice());
          assertTrue(response.isAvailable());

          try {
            String json = mapper.writeValueAsString(response);
            assertTrue(json.contains("\"unitPrice\":1.24"));
            assertTrue(json.contains("\"available\":true"));
            assertFalse(json.contains("stockOnHand"));
            assertFalse(json.contains("reorderLevel"));
            assertFalse(json.contains("version"));
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        })
        .verifyComplete();
  }

  @Test
  public void shouldUseBigDecimalRoundingAndValidateCatalogValues() {

    Ingredient ingredient = catalogIngredient("2.345",true,25,5,0L);
    assertEquals(new BigDecimal("2.35"),ingredient.getUnitPrice());

    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    assertTrue(validator.validate(ingredient).isEmpty());

    ingredient.setUnitPrice(new BigDecimal("-0.01"));
    assertFalse(validator.validate(ingredient).isEmpty());
  }

  @Test
  public void shouldRejectStockAdjustmentThatWouldBecomeNegative() {

    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Ingredient ingredient = catalogIngredient("1.00",true,5,2,3L);
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(ingredient));

    StockAdjustmentRequest request = new StockAdjustmentRequest();
    request.setQuantity(-6);
    request.setExpectedVersion(3L);

    StepVerifier.create(new IngredientController(ingredientRepo)
        .adjustStock("FLTO",request,authentication("ROLE_ADMIN")))
        .expectErrorSatisfies(error -> assertApiStatus(error,422))
        .verify();

    verify(ingredientRepo,never()).save(any(Ingredient.class));
  }

  @Test
  public void shouldTranslateVersionConflictsToConflict() {

    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Ingredient ingredient = catalogIngredient("1.00",true,5,2,3L);
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(ingredient));

    StockAdjustmentRequest staleRequest = new StockAdjustmentRequest();
    staleRequest.setQuantity(1);
    staleRequest.setExpectedVersion(2L);

    IngredientController controller = new IngredientController(ingredientRepo);

    StepVerifier.create(controller.adjustStock(
        "FLTO",staleRequest,authentication("ROLE_ADMIN")))
        .expectErrorSatisfies(error -> assertApiStatus(error,409))
        .verify();

    StockAdjustmentRequest concurrentRequest = new StockAdjustmentRequest();
    concurrentRequest.setQuantity(1);
    concurrentRequest.setExpectedVersion(3L);

    when(ingredientRepo.save(ingredient)).thenReturn(
        Mono.error(new OptimisticLockingFailureException("synthetic conflict")));

    StepVerifier.create(controller.adjustStock(
        "FLTO",concurrentRequest,authentication("ROLE_ADMIN")))
        .expectErrorSatisfies(error -> assertApiStatus(error,409))
        .verify();
  }

  @Test
  public void shouldAllowOnlyAdminToModifyOperationalData() {

    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Ingredient ingredient = catalogIngredient("1.00",true,5,2,3L);

    IngredientCatalogUpdateRequest request = new IngredientCatalogUpdateRequest();
    request.setUnitPrice(new BigDecimal("1.50"));
    request.setExpectedVersion(3L);

    IngredientController controller = new IngredientController(ingredientRepo);

    StepVerifier.create(controller.updateCatalog(
        "FLTO",request,authentication("ROLE_USER")))
        .expectErrorSatisfies(error -> assertApiStatus(error,403))
        .verify();

    verify(ingredientRepo,never()).findById(any(String.class));

    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(ingredient));
    when(ingredientRepo.save(ingredient)).thenReturn(Mono.just(ingredient));

    StepVerifier.create(controller.updateCatalog(
        "FLTO",request,authentication("ROLE_ADMIN")))
        .assertNext(response -> {
          assertEquals(new BigDecimal("1.50"),response.getUnitPrice());
          assertEquals(5,response.getStockOnHand());
          assertEquals(3L,response.getVersion());
        })
        .verifyComplete();
  }

  @Test
  public void shouldPersistTypedIngredientClassificationMetadata() {
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Ingredient ingredient = catalogIngredient("1.00",true,5,2,3L);
    IngredientRequest request = new IngredientRequest();
    request.setId("FLTO");
    request.setName("Flour Tortilla");
    request.setType(Type.WRAP);
    request.setDietaryTags(EnumSet.of(
        DietaryTag.VEGAN,DietaryTag.VEGETARIAN));
    request.setAllergens(EnumSet.of(Allergen.GLUTEN));
    request.setSpiceLevel(SpiceLevel.NONE);
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(ingredient));
    when(ingredientRepo.save(ingredient)).thenReturn(Mono.just(ingredient));

    StepVerifier.create(new IngredientController(ingredientRepo)
        .updateIngredient("FLTO",request))
        .assertNext(response -> {
          assertEquals(request.getDietaryTags(),response.getBody().getDietaryTags());
          assertEquals(request.getAllergens(),response.getBody().getAllergens());
          assertEquals(SpiceLevel.NONE,response.getBody().getSpiceLevel());
        })
        .verifyComplete();
  }

  private Ingredient catalogIngredient(String price,boolean available,
      int stock,int reorderLevel,Long version) {

    Ingredient ingredient = new Ingredient(
        "FLTO","Flour Tortilla",Type.WRAP,
        new BigDecimal(price),available,stock,reorderLevel);
    ingredient.setVersion(version);
    return ingredient;
  }

  private Authentication authentication(String role) {
    Authentication authentication = Mockito.mock(Authentication.class);
    Mockito.doReturn(Collections.singletonList(new SimpleGrantedAuthority(role)))
        .when(authentication)
        .getAuthorities();
    return authentication;
  }

  private void assertApiStatus(Throwable error,int expectedStatus) {
    assertTrue(error instanceof ApiException);
    assertEquals(expectedStatus,((ApiException) error).getStatus().value());
  }
}
