package tacos.web.api;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.Ingredient;
import tacos.data.TacoRepository;
import tacos.web.api.dto.ApiDtos.TacoCatalogResponse;
import tacos.web.api.dto.ApiDtos.TacoClassificationResponse;
import tacos.web.api.dto.ApiDtos.TacoDesignRequest;
import tacos.web.api.dto.ApiDtos.TacoDesignValidationResponse;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.mapper.ApiMapper;
import tacos.web.api.TacoClassificationService.ClassifiedTaco;

@RestController
@RequestMapping(path = "/api/tacos", produces = "application/json")
public class TacoController {
  private TacoRepository tacoRepo;
  private TacoClassificationService classificationService;
  private TacoDesignValidator designValidator;

  public TacoController(TacoRepository tacoRepo,
      TacoClassificationService classificationService,
      TacoDesignValidator designValidator) {
    this.tacoRepo = tacoRepo;
    this.classificationService = classificationService;
    this.designValidator = designValidator;
  }

  @GetMapping(params="recent")
  public Flux<TacoCatalogResponse> recentTacos() {
    return tacoRepo.findAll().take(12)
        .concatMap(classificationService::classify)
        .map(ApiMapper::toResponse);
  }

  @PostMapping(consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<TacoCatalogResponse> postTaco(@RequestBody Taco taco) {
    List<String> ingredientIds = ingredientIds(taco);
    return designValidator.validate(taco.getName(),ingredientIds)
        .flatMap(result -> {
          if (!result.isValid()) {
            return Mono.error(designValidator.invalidDesign(result));
          }
          taco.setIngredients(result.getContext().getResolvedIngredients());
          return tacoRepo.save(taco)
              .map(saved -> new ClassifiedTaco(
                  saved,result.getContext().getResolvedIngredients(),
                  result.getContext().getClassification()));
        })
        .map(ApiMapper::toResponse);
  }

  @PostMapping(path="/validate",consumes="application/json")
  public Mono<TacoDesignValidationResponse> validate(
      @Valid @RequestBody TacoDesignRequest request) {
    return designValidator.validate(request.getName(),request.getIngredientIds())
        .map(ApiMapper::toResponse);
  }

  @GetMapping("/{id}")
  public Mono<TacoCatalogResponse> tacoById(@PathVariable("id") String id) {
    return findTaco(id)
        .flatMap(classificationService::classify)
        .map(ApiMapper::toResponse);
  }

  @GetMapping("/{id}/classification")
  public Mono<TacoClassificationResponse> classification(
      @PathVariable("id") String id) {
    return findTaco(id)
        .flatMap(classificationService::classify)
        .map(ApiMapper::toClassificationResponse);
  }

  private Mono<Taco> findTaco(String id) {
    return tacoRepo.findById(id)
        .switchIfEmpty(Mono.error(ApiException.notFound(
            "TACO_NOT_FOUND","Taco does not exist.")));
  }

  private List<String> ingredientIds(Taco taco) {
    List<Ingredient> ingredients = taco.getIngredients() != null
        ? taco.getIngredients()
        : Collections.emptyList();
    return ingredients.stream()
        .map(Ingredient::getId)
        .collect(Collectors.toList());
  }

}
