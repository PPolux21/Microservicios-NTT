package tacos.web.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import lombok.Data;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.data.IngredientRepository;
import tacos.web.api.TacoClassificationService.TacoClassification;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.error.ApiProblem.Violation;

@Service
public class TacoDesignValidator {

  private final IngredientRepository ingredientRepo;
  private final TacoClassificationService classificationService;
  private final List<TacoDesignRule> rules;

  public TacoDesignValidator(IngredientRepository ingredientRepo,
      TacoClassificationService classificationService,
      List<TacoDesignRule> rules) {
    this.ingredientRepo = ingredientRepo;
    this.classificationService = classificationService;
    this.rules = new ArrayList<>(rules);
  }

  public Mono<ValidationResult> validate(String name,
      List<String> requestedIngredientIds) {
    List<String> ids = requestedIngredientIds != null
        ? new ArrayList<>(requestedIngredientIds)
        : Collections.emptyList();

    return Flux.fromIterable(ids)
        .distinct()
        .concatMap(id -> ingredientRepo.findById(id)
            .switchIfEmpty(Mono.error(ApiException.unprocessable(
                "INGREDIENT_NOT_FOUND","Ingredient does not exist: " + id))))
        .collectMap(Ingredient::getId,ingredient -> ingredient,LinkedHashMap::new)
        .map(byId -> ids.stream().map(byId::get).collect(Collectors.toList()))
        .map(ingredients -> validateResolved(name,ids,ingredients));
  }

  public ValidationResult validateResolved(String name,
      List<String> requestedIngredientIds,List<Ingredient> ingredients) {
    List<String> ids = requestedIngredientIds != null
        ? new ArrayList<>(requestedIngredientIds)
        : Collections.emptyList();
    List<Ingredient> resolved = ingredients != null
        ? new ArrayList<>(ingredients)
        : Collections.emptyList();
    TacoClassification classification =
        classificationService.classifyIngredients(resolved);
    TacoDesignContext context = new TacoDesignContext(
        name,ids,resolved,classification);
    return validate(context);
  }

  public ValidationResult validate(TacoDesignContext context) {
    List<RuleViolation> violations = rules.stream()
        .flatMap(rule -> rule.validate(context).stream())
        .sorted((left,right) -> {
          int codeOrder = left.getCode().compareTo(right.getCode());
          return codeOrder != 0
              ? codeOrder
              : left.getMessage().compareTo(right.getMessage());
        })
        .collect(Collectors.toList());
    return new ValidationResult(context,violations);
  }

  public ApiException invalidDesign(ValidationResult result) {
    List<Violation> details = result.getViolations().stream()
        .map(violation -> new Violation(
            violation.getCode(),violation.getMessage()))
        .collect(Collectors.toList());
    return ApiException.unprocessable(
        "TACO_DESIGN_INVALID","Taco design violates one or more rules.",details);
  }

  public interface TacoDesignRule {
    List<RuleViolation> validate(TacoDesignContext context);
  }

  @Data
  @AllArgsConstructor
  public static class RuleViolation {
    private String code;
    private String message;
  }

  @Data
  @AllArgsConstructor
  public static class TacoDesignContext {
    private String name;
    private List<String> requestedIngredientIds;
    private List<Ingredient> resolvedIngredients;
    private TacoClassification classification;
  }

  @Data
  @AllArgsConstructor
  public static class ValidationResult {
    private TacoDesignContext context;
    private List<RuleViolation> violations;

    public boolean isValid() {
      return violations.isEmpty();
    }
  }
}
