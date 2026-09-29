package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Allergen;
import tacos.Ingredient.DietaryTag;
import tacos.Ingredient.SpiceLevel;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.data.IngredientRepository;
import tacos.data.TacoRepository;
import tacos.web.api.dto.ApiDtos.TacoCatalogResponse;
import tacos.web.api.dto.ApiDtos.TacoDesignValidationResponse;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

public class TacoControllerTest {

  @Test
  public void shouldReturnRecentTacosWithDerivedClassification() {
    Taco[] tacos = new Taco[16];
    for (int index = 0; index < tacos.length; index++) {
      tacos[index] = testTaco(String.valueOf(index + 1));
    }

    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    when(tacoRepo.findAll()).thenReturn(Flux.just(tacos));
    when(ingredientRepo.findById("LETC"))
        .thenReturn(Mono.just(plantIngredient("LETC")));
    when(ingredientRepo.findById("SLSA"))
        .thenReturn(Mono.just(spicyPlantIngredient()));

    client(tacoRepo,ingredientRepo).get().uri("/api/tacos?recent")
      .exchange()
      .expectStatus().isOk()
      .expectBody()
        .jsonPath("$").isArray()
        .jsonPath("$[0].id").isEqualTo("1")
        .jsonPath("$[0].classification.dietaryTags").isArray()
        .jsonPath("$[0].classification.spiceLevel").isEqualTo("HOT")
        .jsonPath("$[0].classification.disclaimer").isNotEmpty()
        .jsonPath("$[11].id").isEqualTo("12")
        .jsonPath("$[12]").doesNotExist();
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

  private WebTestClient client(TacoRepository tacoRepo,
      IngredientRepository ingredientRepo) {
    return WebTestClient.bindToController(controller(tacoRepo,ingredientRepo))
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
    return new TacoController(tacoRepo,classification,validator);
  }

  private Taco testTaco(String id) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName("Test Taco " + id);
    taco.setIngredients(Arrays.asList(
        ingredientReference("LETC"),ingredientReference("SLSA")));
    return taco;
  }

  private Ingredient ingredientReference(String id) {
    return new Ingredient(id,id,Type.VEGGIES);
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
}
