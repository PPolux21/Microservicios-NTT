package tacos.web.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tacos.Ingredient;
import tacos.Ingredient.DietaryTag;
import tacos.Ingredient.SpiceLevel;
import tacos.Ingredient.Type;
import tacos.web.api.TacoDesignValidator.RuleViolation;
import tacos.web.api.TacoDesignValidator.TacoDesignRule;

@Configuration
public class TacoDesignRules {

  static final int MIN_INGREDIENTS = 2;
  static final int MAX_INGREDIENTS = 12;

  @Bean
  public TacoDesignRule baseRule() {
    return context -> {
      long bases = context.getResolvedIngredients().stream()
          .filter(ingredient -> ingredient.getType() == Type.WRAP)
          .count();
      if (bases == 1) {
        return Collections.emptyList();
      }
      String code = bases == 0 ? "BASE_REQUIRED" : "TOO_MANY_BASES";
      return one(code,"A taco design must contain exactly one base.");
    };
  }

  @Bean
  public TacoDesignRule ingredientCountRule() {
    return context -> {
      int count = context.getRequestedIngredientIds().size();
      if (count < MIN_INGREDIENTS) {
        return one("TOO_FEW_INGREDIENTS",
            "A taco design must contain at least 2 ingredients.");
      }
      if (count > MAX_INGREDIENTS) {
        return one("TOO_MANY_INGREDIENTS",
            "A taco design must contain at most 12 ingredients.");
      }
      return Collections.emptyList();
    };
  }

  @Bean
  public TacoDesignRule duplicateIngredientRule() {
    return context -> {
      Set<String> seen = new LinkedHashSet<>();
      Set<String> duplicates = context.getRequestedIngredientIds().stream()
          .filter(id -> !seen.add(id))
          .collect(Collectors.toCollection(LinkedHashSet::new));
      return duplicates.isEmpty()
          ? Collections.emptyList()
          : one("DUPLICATE_INGREDIENT",
              "Ingredients must not be repeated: "
                  + String.join(", ",duplicates) + ".");
    };
  }

  @Bean
  public TacoDesignRule availabilityRule() {
    return context -> {
      List<String> unavailable = context.getResolvedIngredients().stream()
          .filter(ingredient -> !ingredient.isAvailable())
          .map(Ingredient::getId)
          .distinct()
          .sorted()
          .collect(Collectors.toList());
      return unavailable.isEmpty()
          ? Collections.emptyList()
          : one("INGREDIENT_UNAVAILABLE",
              "Unavailable ingredients: "
                  + String.join(", ",unavailable) + ".");
    };
  }

  @Bean
  public TacoDesignRule extremeSpiceRule(
      @Value("${tacocloud.design-rules.allow-extreme-spice:false}")
      boolean allowExtremeSpice) {
    return context -> !allowExtremeSpice
        && context.getClassification().getSpiceLevel() == SpiceLevel.EXTREME
            ? one("EXTREME_SPICE_NOT_ALLOWED",
                "Extreme spice designs are disabled by configuration.")
            : Collections.emptyList();
  }

  @Bean
  public TacoDesignRule veganModeRule(
      @Value("${tacocloud.design-rules.vegan-only:false}")
      boolean veganOnly) {
    return context -> veganOnly
        && !context.getClassification().getDietaryTags()
            .contains(DietaryTag.VEGAN)
            ? one("VEGAN_DESIGN_REQUIRED",
                "Current configuration accepts only vegan designs.")
            : Collections.emptyList();
  }

  private List<RuleViolation> one(String code,String message) {
    return Collections.singletonList(new RuleViolation(code,message));
  }
}
