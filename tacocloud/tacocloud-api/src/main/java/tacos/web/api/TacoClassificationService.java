package tacos.web.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import lombok.Data;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Ingredient.Allergen;
import tacos.Ingredient.DietaryTag;
import tacos.Ingredient.SpiceLevel;
import tacos.Taco;
import tacos.data.IngredientRepository;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

@Service
public class TacoClassificationService {

  public static final String DISCLAIMER =
      "La clasificación dietaria y de alérgenos se basa exclusivamente "
      + "en la metadata configurada para los ingredientes y no sustituye "
      + "controles reales de contaminación cruzada.";

  private final IngredientRepository ingredientRepo;

  public TacoClassificationService(IngredientRepository ingredientRepo) {
    this.ingredientRepo = ingredientRepo;
  }

  public Mono<ClassifiedTaco> classify(Taco taco) {
    List<Ingredient> references = taco.getIngredients() != null
        ? taco.getIngredients()
        : Collections.emptyList();

    return Flux.fromIterable(references)
        .concatMap(reference -> {
          if (reference == null || reference.getId() == null) {
            return Mono.error(ApiException.unprocessable(
                "INGREDIENT_NOT_FOUND","Taco contains an invalid ingredient reference."));
          }
          return ingredientRepo.findById(reference.getId())
              .switchIfEmpty(Mono.error(ApiException.unprocessable(
                  "INGREDIENT_NOT_FOUND",
                  "Ingredient does not exist: " + reference.getId())));
        })
        .collectList()
        .map(ingredients -> new ClassifiedTaco(
            taco,ingredients,classifyIngredients(ingredients)));
  }

  public TacoClassification classifyIngredients(List<Ingredient> ingredients) {
    List<Ingredient> safeIngredients = ingredients != null
        ? ingredients
        : Collections.emptyList();

    Set<DietaryTag> dietaryTags = EnumSet.noneOf(DietaryTag.class);
    if (!safeIngredients.isEmpty()) {
      dietaryTags = EnumSet.allOf(DietaryTag.class);
      for (Ingredient ingredient : safeIngredients) {
        dietaryTags.retainAll(
            ingredient.getDietaryTags() != null
                ? ingredient.getDietaryTags()
                : Collections.emptySet());
      }
    }

    Set<Allergen> allergens = EnumSet.noneOf(Allergen.class);
    SpiceLevel spiceLevel = SpiceLevel.NONE;
    for (Ingredient ingredient : safeIngredients) {
      if (ingredient.getAllergens() != null) {
        allergens.addAll(ingredient.getAllergens());
      }
      SpiceLevel ingredientSpice = ingredient.getSpiceLevel() != null
          ? ingredient.getSpiceLevel()
          : SpiceLevel.NONE;
      if (ingredientSpice.ordinal() > spiceLevel.ordinal()) {
        spiceLevel = ingredientSpice;
      }
    }

    return new TacoClassification(dietaryTags,allergens,spiceLevel);
  }

  @Data
  @AllArgsConstructor
  public static class TacoClassification {
    private Set<DietaryTag> dietaryTags;
    private Set<Allergen> allergens;
    private SpiceLevel spiceLevel;
  }

  @Data
  @AllArgsConstructor
  public static class ClassifiedTaco {
    private Taco taco;
    private List<Ingredient> ingredients;
    private TacoClassification classification;
  }
}
