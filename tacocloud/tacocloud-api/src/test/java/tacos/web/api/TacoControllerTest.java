package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Allergen;
import tacos.Ingredient.DietaryTag;
import tacos.Ingredient.SpiceLevel;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.data.IngredientRepository;
import tacos.data.TacoRepository;
import tacos.data.TacoSearchRepository.TacoSearchPage;
import tacos.data.TacoSearchRepository.TacoSearchQuery;
import tacos.web.api.dto.ApiDtos.TacoCatalogResponse;
import tacos.web.api.dto.ApiDtos.TacoDesignValidationResponse;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

public class TacoControllerTest {

  @Test
  public void shouldReturnFirstSearchPageWithDerivedClassification() {
    Taco[] tacos = new Taco[16];
    for (int index = 0; index < tacos.length; index++) {
      tacos[index] = testTaco(String.valueOf(index + 1));
    }

    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    when(tacoRepo.search(any(TacoSearchQuery.class))).thenReturn(Mono.just(
        new TacoSearchPage(Arrays.asList(tacos).subList(0,12),0,12,16)));

    client(tacoRepo,ingredientRepo).get()
      .uri("/api/tacos?page=0&size=12&sort=createdAt,desc")
      .exchange()
      .expectStatus().isOk()
      .expectBody()
        .jsonPath("$.items").isArray()
        .jsonPath("$.items[0].id").isEqualTo("1")
        .jsonPath("$.items[0].classification.dietaryTags").isArray()
        .jsonPath("$.items[0].classification.spiceLevel").isEqualTo("HOT")
        .jsonPath("$.items[0].classification.disclaimer").isNotEmpty()
        .jsonPath("$.items[11].id").isEqualTo("12")
        .jsonPath("$.items[12]").doesNotExist()
        .jsonPath("$.page").isEqualTo(0)
        .jsonPath("$.size").isEqualTo(12)
        .jsonPath("$.totalElements").isEqualTo(16)
        .jsonPath("$.totalPages").isEqualTo(2);
  }

  @Test
  public void shouldIgnoreClientClassificationAndUsePersistedIngredients() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    when(tacoRepo.save(any(Taco.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

    Ingredient meat = catalogIngredient("MEAT");
    meat.setSpiceLevel(SpiceLevel.MILD);
    Ingredient base = catalogIngredient("BASE");
    base.setType(Type.WRAP);
    base.setDietaryTags(EnumSet.allOf(DietaryTag.class));
    when(ingredientRepo.findById("MEAT")).thenReturn(Mono.just(meat));
    when(ingredientRepo.findById("BASE")).thenReturn(Mono.just(base));

    String manipulatedJson = "{"
        + "\"name\":\"Fake Vegan Taco\","
        + "\"dietaryTags\":[\"VEGAN\"],"
        + "\"allergens\":[],"
        + "\"spiceLevel\":\"NONE\","
        + "\"ingredients\":[{\"id\":\"BASE\"},{\"id\":\"MEAT\","
        + "\"dietaryTags\":[\"VEGAN\"],\"spiceLevel\":\"NONE\"}]}";

    client(tacoRepo,ingredientRepo).post().uri("/api/tacos")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(manipulatedJson)
        .exchange()
        .expectStatus().isCreated()
        .expectBody(TacoCatalogResponse.class)
        .value(response -> {
          assertFalse(response.getClassification().getDietaryTags()
              .contains(DietaryTag.VEGAN));
          assertEquals(SpiceLevel.MILD,
              response.getClassification().getSpiceLevel());
        });
  }

  @Test
  public void shouldReturnAllViolationsWithoutSavingAndResolveDuplicateOnce() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Ingredient unavailable = catalogIngredient("MEAT");
    unavailable.setAvailable(false);
    when(ingredientRepo.findById("MEAT")).thenReturn(Mono.just(unavailable));

    String request = "{\"name\":\"Invalid Taco\","
        + "\"ingredientIds\":[\"MEAT\",\"MEAT\"]}";

    client(tacoRepo,ingredientRepo).post().uri("/api/tacos/validate")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus().isOk()
        .expectBody(TacoDesignValidationResponse.class)
        .value(response -> {
          assertFalse(response.isValid());
          Set<String> codes = response.getViolations().stream()
              .map(violation -> violation.getCode())
              .collect(Collectors.toSet());
          assertEquals(new java.util.HashSet<>(Arrays.asList(
              "BASE_REQUIRED","DUPLICATE_INGREDIENT",
              "INGREDIENT_UNAVAILABLE")),codes);
        });

    verify(ingredientRepo,times(1)).findById("MEAT");
    verify(tacoRepo,never()).save(any(Taco.class));
  }

  @Test
  public void shouldReturnClassificationForExistingTaco() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Taco taco = testTaco("TACO-1");
    when(tacoRepo.findById("TACO-1")).thenReturn(Mono.just(taco));
    when(ingredientRepo.findById("LETC"))
        .thenReturn(Mono.just(plantIngredient("LETC")));
    when(ingredientRepo.findById("SLSA"))
        .thenReturn(Mono.just(spicyPlantIngredient()));

    client(tacoRepo,ingredientRepo).get()
        .uri("/api/tacos/TACO-1/classification")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.tacoId").isEqualTo("TACO-1")
        .jsonPath("$.dietaryTags").isArray()
        .jsonPath("$.allergens").isArray()
        .jsonPath("$.spiceLevel").isEqualTo("HOT")
        .jsonPath("$.disclaimer").isNotEmpty();
  }

  @Test
  public void shouldReturnNotFoundForUnknownTaco() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    when(tacoRepo.findById("UNKNOWN")).thenReturn(Mono.empty());

    StepVerifier.create(controller(tacoRepo,ingredientRepo)
        .classification("UNKNOWN"))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof ApiException);
          ApiException apiError = (ApiException) error;
          assertEquals(org.springframework.http.HttpStatus.NOT_FOUND,
              apiError.getStatus());
          assertEquals("TACO_NOT_FOUND",apiError.getCode());
        })
        .verify();
  }

  @Test
  public void shouldReturnDailyTacoWithoutPersisting() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    Taco taco = dailyCandidate("TACO-A");
    when(tacoRepo.findAllByOrderByIdAsc()).thenReturn(Flux.just(taco));
    when(ingredientRepo.findById("BASE"))
        .thenReturn(Mono.just(baseIngredient()));
    when(ingredientRepo.findById("FILL-TACO-A"))
        .thenReturn(Mono.just(plantIngredient("FILL-TACO-A")));

    client(tacoRepo,ingredientRepo).get().uri("/api/tacos/today")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.taco.id").isEqualTo("TACO-A")
        .jsonPath("$.date").isEqualTo("2026-09-29")
        .jsonPath("$.reason").isEqualTo(
            "Seleccionado entre los tacos disponibles para la fecha 2026-09-29.");

    verify(tacoRepo,never()).save(any(Taco.class));
  }

  @Test
  public void shouldReturnUniformNotFoundWhenThereAreNoDailyCandidates() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    when(tacoRepo.findAllByOrderByIdAsc()).thenReturn(Flux.empty());

    client(tacoRepo,ingredientRepo).get().uri("/api/tacos/today")
        .exchange()
        .expectStatus().isNotFound()
        .expectBody()
        .jsonPath("$.code").isEqualTo("NO_TACO_AVAILABLE");
  }

  @Test
  public void shouldRejectUnsafeSortAndExcessivePageSize() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    TacoController controller = controller(tacoRepo,ingredientRepo);
    controller.configureMaxPageSize(50);

    tacos.web.api.dto.ApiDtos.TacoSearchQuery unsafeSort =
        new tacos.web.api.dto.ApiDtos.TacoSearchQuery();
    unsafeSort.setSort("password,asc");
    ApiException sortError = assertThrows(
        ApiException.class,() -> controller.search(unsafeSort));
    assertEquals("INVALID_SORT",sortError.getCode());

    unsafeSort.setSort("createdAt,sideways");
    ApiException directionError = assertThrows(
        ApiException.class,() -> controller.search(unsafeSort));
    assertEquals("INVALID_SORT",directionError.getCode());

    tacos.web.api.dto.ApiDtos.TacoSearchQuery excessive =
        new tacos.web.api.dto.ApiDtos.TacoSearchQuery();
    excessive.setSize(51);
    ApiException sizeError = assertThrows(
        ApiException.class,() -> controller.search(excessive));
    assertEquals("PAGE_SIZE_EXCEEDED",sizeError.getCode());
  }

  @Test
  public void shouldRejectNegativePageAndZeroSizeThroughBeanValidation() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);

    client(tacoRepo,ingredientRepo).get().uri("/api/tacos?page=-1&size=20")
        .exchange().expectStatus().isBadRequest();
    client(tacoRepo,ingredientRepo).get().uri("/api/tacos?page=0&size=0")
        .exchange().expectStatus().isBadRequest();
    client(tacoRepo,ingredientRepo).get().uri(uriBuilder -> uriBuilder
        .path("/api/tacos")
        .queryParam("name",String.join("",java.util.Collections.nCopies(51,"x")))
        .build())
        .exchange().expectStatus().isBadRequest();
    verify(tacoRepo,never()).search(any(TacoSearchQuery.class));
  }

  private WebTestClient client(TacoRepository tacoRepo,
      IngredientRepository ingredientRepo) {
    return WebTestClient.bindToController(controller(tacoRepo,ingredientRepo))
        .controllerAdvice(new TestApiExceptionHandler())
        .build();
  }

  private TacoController controller(TacoRepository tacoRepo,
      IngredientRepository ingredientRepo) {
    TacoClassificationService classification =
        new TacoClassificationService(ingredientRepo);
    TacoDesignRules rules = new TacoDesignRules();
    TacoDesignValidator validator = new TacoDesignValidator(
        ingredientRepo,classification,Arrays.asList(
            rules.baseRule(),rules.ingredientCountRule(),
            rules.duplicateIngredientRule(),rules.availabilityRule(),
            rules.extremeSpiceRule(false),rules.veganModeRule(false)));
    Clock clock = Clock.fixed(
        Instant.parse("2026-09-29T12:00:00Z"),
        ZoneId.of("America/Mexico_City"));
    DailyTacoService dailyTacoService = new DailyTacoService(
        tacoRepo,validator,clock);
    return new TacoController(
        tacoRepo,classification,validator,dailyTacoService);
  }

  private Taco dailyCandidate(String id) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName("Daily " + id);
    taco.setIngredients(Arrays.asList(
        ingredientReference("BASE"),ingredientReference("FILL-" + id)));
    return taco;
  }

  private Ingredient ingredientReference(String id) {
    return new Ingredient(id,id,Type.VEGGIES);
  }

  private Ingredient baseIngredient() {
    Ingredient ingredient = plantIngredient("BASE");
    ingredient.setType(Type.WRAP);
    return ingredient;
  }

  private Taco testTaco(String id) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName("Test Taco " + id);
    taco.setIngredients(Arrays.asList(
        plantIngredient("LETC"),spicyPlantIngredient()));
    return taco;
  }

  private Ingredient plantIngredient(String id) {
    Ingredient ingredient = catalogIngredient(id);
    ingredient.setDietaryTags(EnumSet.allOf(DietaryTag.class));
    return ingredient;
  }

  private Ingredient spicyPlantIngredient() {
    Ingredient ingredient = plantIngredient("SLSA");
    ingredient.setSpiceLevel(SpiceLevel.HOT);
    return ingredient;
  }

  private Ingredient catalogIngredient(String id) {
    Ingredient ingredient = new Ingredient(
        id,id,Type.VEGGIES,new BigDecimal("1.00"),true,10,2);
    ingredient.setDietaryTags(EnumSet.noneOf(DietaryTag.class));
    ingredient.setAllergens(EnumSet.noneOf(Allergen.class));
    ingredient.setSpiceLevel(SpiceLevel.NONE);
    return ingredient;
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
