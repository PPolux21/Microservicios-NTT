package tacos.web.api;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import lombok.Data;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.data.TacoRepository;
import tacos.web.api.TacoClassificationService.ClassifiedTaco;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

@Service
public class DailyTacoService {

  private final TacoRepository tacoRepo;
  private final TacoDesignValidator designValidator;
  private final Clock clock;

  public DailyTacoService(TacoRepository tacoRepo,
      TacoDesignValidator designValidator,Clock clock) {
    this.tacoRepo = tacoRepo;
    this.designValidator = designValidator;
    this.clock = clock;
  }

  public Mono<DailyTacoRecommendation> recommend() {
    LocalDate date = LocalDate.now(clock);
    return candidates()
        .collectList()
        .flatMap(candidates -> select(candidates,date));
  }

  private Flux<ClassifiedTaco> candidates() {
    return tacoRepo.findAllByOrderByIdAsc()
        .concatMap(taco -> designValidator.validate(
            taco.getName(),ingredientIds(taco))
            .filter(TacoDesignValidator.ValidationResult::isValid)
            .map(result -> new ClassifiedTaco(
                taco,result.getContext().getResolvedIngredients(),
                result.getContext().getClassification()))
            .onErrorResume(ApiException.class,error ->
                "INGREDIENT_NOT_FOUND".equals(error.getCode())
                    ? Mono.empty()
                    : Mono.error(error)));
  }

  Mono<DailyTacoRecommendation> select(
      List<ClassifiedTaco> candidates,LocalDate date) {
    if (candidates.isEmpty()) {
      return Mono.error(ApiException.notFound(
          "NO_TACO_AVAILABLE","No valid taco is currently available."));
    }
    List<ClassifiedTaco> ordered = new ArrayList<>(candidates);
    ordered.sort(Comparator.comparing(
        candidate -> candidate.getTaco().getId()));
    int index = Math.floorMod(date.toEpochDay(),ordered.size());
    return Mono.just(new DailyTacoRecommendation(
        ordered.get(index),date,
        "Seleccionado entre los tacos disponibles para la fecha "
            + date + "."));
  }

  private List<String> ingredientIds(Taco taco) {
    List<Ingredient> ingredients = taco.getIngredients() != null
        ? taco.getIngredients()
        : Collections.emptyList();
    return ingredients.stream()
        .map(Ingredient::getId)
        .collect(Collectors.toList());
  }

  @Data
  @AllArgsConstructor
  public static class DailyTacoRecommendation {
    private ClassifiedTaco taco;
    private LocalDate date;
    private String reason;
  }
}
