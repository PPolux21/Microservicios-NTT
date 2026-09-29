package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import tacos.Ingredient;
import tacos.Ingredient.DietaryTag;
import tacos.Ingredient.SpiceLevel;
import tacos.Ingredient.Type;
import tacos.data.IngredientRepository;
import tacos.web.api.TacoDesignValidator.RuleViolation;
import tacos.web.api.TacoDesignValidator.TacoDesignContext;
import tacos.web.api.TacoDesignValidator.TacoDesignRule;
import tacos.web.api.TacoDesignValidator.ValidationResult;

public class TacoDesignValidatorTest {

  private IngredientRepository ingredientRepo;
  private TacoClassificationService classificationService;
  private TacoDesignRules rules;

  @BeforeEach
  public void setup() {
    ingredientRepo = Mockito.mock(IngredientRepository.class);
    classificationService = new TacoClassificationService(ingredientRepo);
    rules = new TacoDesignRules();
  }

  @Test
  public void shouldRequireExactlyOneBase() {
    TacoDesignRule rule = rules.baseRule();
    assertCodes(rule.validate(context(veggie("A"),veggie("B"))),
        "BASE_REQUIRED");
    assertTrue(rule.validate(context(base("BASE"),veggie("A"))).isEmpty());
    assertCodes(rule.validate(context(base("A"),base("B"))),
        "TOO_MANY_BASES");
  }

  @Test
  public void shouldEnforceIngredientCountBoundaries() {
    TacoDesignRule rule = rules.ingredientCountRule();
    assertCodes(rule.validate(contextWithCount(1)),"TOO_FEW_INGREDIENTS");
    assertTrue(rule.validate(contextWithCount(2)).isEmpty());
    assertTrue(rule.validate(contextWithCount(12)).isEmpty());
    assertCodes(rule.validate(contextWithCount(13)),"TOO_MANY_INGREDIENTS");
  }

  @Test
  public void shouldDetectDuplicateIngredientExplicitly() {
    TacoDesignContext duplicate = context(
        Arrays.asList("BASE","TMTO","TMTO"),
        Arrays.asList(base("BASE"),veggie("TMTO"),veggie("TMTO")));
    assertCodes(rules.duplicateIngredientRule().validate(duplicate),
        "DUPLICATE_INGREDIENT");
  }

  @Test
  public void shouldUseCatalogAvailabilityWithoutInferringFromStock() {
    Ingredient paused = veggie("PAUSED");
    paused.setAvailable(false);
    paused.setStockOnHand(100);
    assertCodes(rules.availabilityRule().validate(
        context(base("BASE"),paused)),"INGREDIENT_UNAVAILABLE");
  }

  @Test
  public void shouldApplyConfigurableExtremeSpiceRule() {
    Ingredient extreme = veggie("FIRE");
    extreme.setSpiceLevel(SpiceLevel.EXTREME);
    TacoDesignContext context = context(base("BASE"),extreme);
    assertCodes(rules.extremeSpiceRule(false).validate(context),
        "EXTREME_SPICE_NOT_ALLOWED");
    assertTrue(rules.extremeSpiceRule(true).validate(context).isEmpty());
  }

  @Test
  public void shouldApplyConfigurableVeganModeUsingDerivedClassification() {
    Ingredient meat = veggie("MEAT");
    meat.setDietaryTags(EnumSet.noneOf(DietaryTag.class));
    TacoDesignContext context = context(base("BASE"),meat);
    assertCodes(rules.veganModeRule(true).validate(context),
        "VEGAN_DESIGN_REQUIRED");
    assertTrue(rules.veganModeRule(false).validate(context).isEmpty());
  }

  @Test
  public void shouldAcceptValidDesignWithNoViolations() {
    TacoDesignValidator validator = validator(defaultRules());
    ValidationResult result = validator.validate(
        context(base("BASE"),veggie("TMTO")));
    assertTrue(result.isValid());
    assertTrue(result.getViolations().isEmpty());
  }

  @Test
  public void shouldCollectAllViolationsIndependentlyOfRuleOrder() {
    Ingredient unavailable = veggie("TMTO");
    unavailable.setAvailable(false);
    TacoDesignContext context = context(
        Arrays.asList("TMTO","TMTO"),
        Arrays.asList(unavailable,unavailable));

    List<TacoDesignRule> forward = Arrays.asList(
        rules.baseRule(),rules.duplicateIngredientRule(),
        rules.availabilityRule());
    List<TacoDesignRule> reverse = new ArrayList<>(forward);
    Collections.reverse(reverse);

    List<String> first = codes(validator(forward).validate(context));
    List<String> second = codes(validator(reverse).validate(context));
    assertEquals(Arrays.asList(
        "BASE_REQUIRED","DUPLICATE_INGREDIENT",
        "INGREDIENT_UNAVAILABLE"),first);
    assertEquals(first,second);
  }

  @Test
  public void shouldIncludeInjectedFakeRuleWithoutValidatorChanges() {
    TacoDesignRule fake = context -> Collections.singletonList(
        new RuleViolation("FAKE_VIOLATION","Injected by the test."));
    ValidationResult result = validator(Collections.singletonList(fake))
        .validate(context(base("BASE"),veggie("TMTO")));
    assertCodes(result.getViolations(),"FAKE_VIOLATION");
  }

  private List<TacoDesignRule> defaultRules() {
    return Arrays.asList(
        rules.baseRule(),rules.ingredientCountRule(),
        rules.duplicateIngredientRule(),rules.availabilityRule(),
        rules.extremeSpiceRule(false),rules.veganModeRule(false));
  }

  private TacoDesignValidator validator(List<TacoDesignRule> designRules) {
    return new TacoDesignValidator(
        ingredientRepo,classificationService,designRules);
  }

  private TacoDesignContext context(Ingredient... ingredients) {
    List<Ingredient> list = Arrays.asList(ingredients);
    return context(list.stream().map(Ingredient::getId)
        .collect(Collectors.toList()),list);
  }

  private TacoDesignContext context(List<String> ids,
      List<Ingredient> ingredients) {
    return new TacoDesignContext(
        "Test Taco",ids,ingredients,
        classificationService.classifyIngredients(ingredients));
  }

  private TacoDesignContext contextWithCount(int count) {
    List<String> ids = new ArrayList<>();
    List<Ingredient> ingredients = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      String id = "ING" + index;
      ids.add(id);
      ingredients.add(veggie(id));
    }
    return context(ids,ingredients);
  }

  private Ingredient base(String id) {
    Ingredient ingredient = ingredient(id,Type.WRAP);
    ingredient.setDietaryTags(EnumSet.allOf(DietaryTag.class));
    return ingredient;
  }

  private Ingredient veggie(String id) {
    return ingredient(id,Type.VEGGIES);
  }

  private Ingredient ingredient(String id,Type type) {
    return new Ingredient(
        id,id,type,new BigDecimal("1.00"),true,100,10);
  }

  private List<String> codes(ValidationResult result) {
    return result.getViolations().stream()
        .map(RuleViolation::getCode)
        .collect(Collectors.toList());
  }

  private void assertCodes(List<RuleViolation> violations,
      String... expected) {
    Set<String> actual = violations.stream()
        .map(RuleViolation::getCode)
        .collect(Collectors.toSet());
    assertEquals(new java.util.HashSet<>(Arrays.asList(expected)),actual);
  }
}
