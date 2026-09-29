package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
import tacos.web.api.DailyTacoService.DailyTacoRecommendation;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

public class DailyTacoServiceTest {

  private static final ZoneId ZONE = ZoneId.of("America/Mexico_City");

  @Test
  public void shouldReturnSameIdTwiceOnSameDateWithoutSaving() {
    TacoRepository tacoRepo = repository(taco("C"),taco("A"),taco("B"));
    DailyTacoService service = service(
        tacoRepo,validator(Collections.emptyList()),clock("2026-09-29"));

    StepVerifier.create(Mono.zip(service.recommend(),service.recommend()))
        .assertNext(results ->
            assertEquals(results.getT1().getTaco().getTaco().getId(),
                results.getT2().getTaco().getTaco().getId()))
        .verifyComplete();

    verify(tacoRepo,never()).save(any(Taco.class));
  }

  @Test
  public void shouldSelectExpectedIndexForTwoDates() {
    List<String> orderedIds = Arrays.asList("A","B","C");
    TacoRepository tacoRepo = repository(taco("C"),taco("A"),taco("B"));
    TacoDesignValidator validator = validator(Collections.emptyList());
    LocalDate firstDate = LocalDate.of(2026,9,29);
    LocalDate secondDate = firstDate.plusDays(1);
    DailyTacoService first = service(tacoRepo,validator,clock(firstDate));
    DailyTacoService second = service(tacoRepo,validator,clock(secondDate));

    StepVerifier.create(Mono.zip(first.recommend(),second.recommend()))
        .assertNext(results -> {
          assertEquals(expectedId(orderedIds,firstDate),
              results.getT1().getTaco().getTaco().getId());
          assertEquals(expectedId(orderedIds,secondDate),
              results.getT2().getTaco().getTaco().getId());
        })
        .verifyComplete();
  }

  @Test
  public void shouldIgnorePhysicalCandidateOrder() {
    TacoDesignValidator validator = validator(Collections.emptyList());
    Clock clock = clock("2026-09-29");
    DailyTacoService first = service(
        repository(taco("A"),taco("B"),taco("C")),validator,clock);
    DailyTacoService second = service(
        repository(taco("C"),taco("A"),taco("B")),validator,clock);

    StepVerifier.create(Mono.zip(first.recommend(),second.recommend()))
        .assertNext(results -> assertEquals(
            results.getT1().getTaco().getTaco().getId(),
            results.getT2().getTaco().getTaco().getId()))
        .verifyComplete();
  }

  @Test
  public void shouldReturnNotFoundWithoutCandidates() {
    DailyTacoService service = service(
        repository(),validator(Collections.emptyList()),clock("2026-09-29"));

    StepVerifier.create(service.recommend())
        .expectErrorSatisfies(error -> {
          assertEquals(ApiException.class,error.getClass());
          assertEquals("NO_TACO_AVAILABLE",((ApiException) error).getCode());
        })
        .verify();
  }

  @Test
  public void shouldExcludeTacoWhoseCurrentIngredientIsUnavailable() {
    TacoRepository tacoRepo = repository(taco("A"),taco("B"));
    DailyTacoService service = service(
        tacoRepo,validator(Collections.singletonList("FILL-A")),
        clock("2026-09-28"));

    StepVerifier.create(service.recommend())
        .assertNext(result -> assertEquals(
            "B",result.getTaco().getTaco().getId()))
        .verifyComplete();
  }

  @Test
  public void shouldSkipTacoWithMissingCurrentIngredient() {
    TacoRepository tacoRepo = repository(taco("A"),taco("B"));
    DailyTacoService service = service(
        tacoRepo,validator(Collections.emptyList(),"FILL-A"),
        clock("2026-09-28"));

    StepVerifier.create(service.recommend())
        .assertNext(result -> assertEquals(
            "B",result.getTaco().getTaco().getId()))
        .verifyComplete();
  }

  @Test
  public void shouldUseConfiguredClockZoneForLogicalDate() {
    Clock boundaryClock = Clock.fixed(
        Instant.parse("2026-09-29T01:00:00Z"),ZONE);
    DailyTacoService service = service(
        repository(taco("A")),validator(Collections.emptyList()),boundaryClock);

    StepVerifier.create(service.recommend().map(DailyTacoRecommendation::getDate))
        .expectNext(LocalDate.of(2026,9,28))
        .verifyComplete();
  }

  private DailyTacoService service(TacoRepository tacoRepo,
      TacoDesignValidator validator,Clock clock) {
    return new DailyTacoService(tacoRepo,validator,clock);
  }

  private TacoRepository repository(Taco... tacos) {
    TacoRepository repository = Mockito.mock(TacoRepository.class);
    when(repository.findAllByOrderByIdAsc())
        .thenReturn(Flux.fromArray(tacos));
    return repository;
  }

  private TacoDesignValidator validator(List<String> unavailableIds) {
    return validator(unavailableIds,null);
  }

  private TacoDesignValidator validator(
      List<String> unavailableIds,String missingId) {
    IngredientRepository ingredientRepo = Mockito.mock(IngredientRepository.class);
    when(ingredientRepo.findById(any(String.class))).thenAnswer(invocation -> {
      String id = invocation.getArgument(0);
      if (id.equals(missingId)) {
        return Mono.empty();
      }
      Ingredient ingredient = ingredient(id);
      ingredient.setAvailable(!unavailableIds.contains(id));
      return Mono.just(ingredient);
    });
    TacoClassificationService classification =
        new TacoClassificationService(ingredientRepo);
    TacoDesignRules rules = new TacoDesignRules();
    return new TacoDesignValidator(
        ingredientRepo,classification,Arrays.asList(
            rules.baseRule(),rules.ingredientCountRule(),
            rules.duplicateIngredientRule(),rules.availabilityRule(),
            rules.extremeSpiceRule(false),rules.veganModeRule(false)));
  }

  private Taco taco(String id) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName("Candidate " + id);
    taco.setIngredients(Arrays.asList(
        reference("BASE"),reference("FILL-" + id)));
    return taco;
  }

  private Ingredient reference(String id) {
    return new Ingredient(id,id,Type.VEGGIES);
  }

  private Ingredient ingredient(String id) {
    Type type = "BASE".equals(id) ? Type.WRAP : Type.VEGGIES;
    Ingredient ingredient = new Ingredient(
        id,id,type,new BigDecimal("1.00"),true,10,2);
    ingredient.setDietaryTags(EnumSet.allOf(DietaryTag.class));
    ingredient.setAllergens(EnumSet.noneOf(Allergen.class));
    ingredient.setSpiceLevel(SpiceLevel.NONE);
    return ingredient;
  }

  private Clock clock(String date) {
    return clock(LocalDate.parse(date));
  }

  private Clock clock(LocalDate date) {
    return Clock.fixed(date.atTime(12,0).atZone(ZONE).toInstant(),ZONE);
  }

  private String expectedId(List<String> orderedIds,LocalDate date) {
    return orderedIds.get(Math.floorMod(date.toEpochDay(),orderedIds.size()));
  }
}
