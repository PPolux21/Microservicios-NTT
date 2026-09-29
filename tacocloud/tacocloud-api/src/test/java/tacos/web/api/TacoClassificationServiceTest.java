package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import tacos.Ingredient;
import tacos.Ingredient.Allergen;
import tacos.Ingredient.DietaryTag;
import tacos.Ingredient.SpiceLevel;
import tacos.Ingredient.Type;
import tacos.data.IngredientRepository;
import tacos.web.api.TacoClassificationService.TacoClassification;

public class TacoClassificationServiceTest {

  private TacoClassificationService service;

  @BeforeEach
  public void setup() {
    service = new TacoClassificationService(
        Mockito.mock(IngredientRepository.class));
  }

  @Test
  public void shouldRequireEveryIngredientForDietaryTags() {
    Ingredient allTagsA = ingredient("A",
        EnumSet.allOf(DietaryTag.class),Collections.emptySet(),SpiceLevel.NONE);
    Ingredient allTagsB = ingredient("B",
        EnumSet.allOf(DietaryTag.class),Collections.emptySet(),SpiceLevel.NONE);

    TacoClassification fullyPlantBased = service.classifyIngredients(
        Arrays.asList(allTagsA,allTagsB));
    assertEquals(EnumSet.allOf(DietaryTag.class),
        fullyPlantBased.getDietaryTags());

    Ingredient notVegan = ingredient("C",
        EnumSet.of(DietaryTag.VEGETARIAN,DietaryTag.GLUTEN_FREE),
        Collections.emptySet(),SpiceLevel.NONE);
    TacoClassification mixed = service.classifyIngredients(
        Arrays.asList(allTagsA,allTagsB,notVegan));

    assertFalse(mixed.getDietaryTags().contains(DietaryTag.VEGAN));
    assertTrue(mixed.getDietaryTags().contains(DietaryTag.VEGETARIAN));
    assertTrue(mixed.getDietaryTags().contains(DietaryTag.GLUTEN_FREE));

    Ingredient containsGluten = ingredient("D",
        EnumSet.of(DietaryTag.VEGAN,DietaryTag.VEGETARIAN),
        EnumSet.of(Allergen.GLUTEN),SpiceLevel.NONE);
    TacoClassification withGluten = service.classifyIngredients(
        Arrays.asList(allTagsA,containsGluten));
    assertFalse(withGluten.getDietaryTags().contains(DietaryTag.GLUTEN_FREE));
  }

  @Test
  public void shouldReturnExactAllergenUnionWithoutDuplicates() {
    Ingredient a = ingredient("A",Collections.emptySet(),
        EnumSet.of(Allergen.GLUTEN),SpiceLevel.NONE);
    Ingredient b = ingredient("B",Collections.emptySet(),
        EnumSet.of(Allergen.DAIRY),SpiceLevel.NONE);
    Ingredient c = ingredient("C",Collections.emptySet(),
        EnumSet.of(Allergen.GLUTEN,Allergen.SOY),SpiceLevel.NONE);

    TacoClassification result = service.classifyIngredients(
        Arrays.asList(a,b,c));

    assertEquals(EnumSet.of(Allergen.GLUTEN,Allergen.DAIRY,Allergen.SOY),
        result.getAllergens());
  }

  @Test
  public void shouldUseMaximumSpiceRegardlessOfIngredientOrder() {
    Ingredient none = ingredient("A",Collections.emptySet(),
        Collections.emptySet(),SpiceLevel.NONE);
    Ingredient mild = ingredient("B",Collections.emptySet(),
        Collections.emptySet(),SpiceLevel.MILD);
    Ingredient hot = ingredient("C",Collections.emptySet(),
        Collections.emptySet(),SpiceLevel.HOT);
    Ingredient medium = ingredient("D",Collections.emptySet(),
        Collections.emptySet(),SpiceLevel.MEDIUM);

    assertEquals(SpiceLevel.HOT,service.classifyIngredients(
        Arrays.asList(none,mild,hot,medium)).getSpiceLevel());
    assertEquals(SpiceLevel.HOT,service.classifyIngredients(
        Arrays.asList(medium,hot,mild,none)).getSpiceLevel());
  }

  private Ingredient ingredient(String id,
      java.util.Set<DietaryTag> dietaryTags,
      java.util.Set<Allergen> allergens,SpiceLevel spiceLevel) {
    Ingredient ingredient = new Ingredient(id,id,Type.VEGGIES);
    ingredient.setDietaryTags(dietaryTags.isEmpty()
        ? EnumSet.noneOf(DietaryTag.class)
        : EnumSet.copyOf(dietaryTags));
    ingredient.setAllergens(allergens.isEmpty()
        ? EnumSet.noneOf(Allergen.class)
        : EnumSet.copyOf(allergens));
    ingredient.setSpiceLevel(spiceLevel);
    return ingredient;
  }
}
