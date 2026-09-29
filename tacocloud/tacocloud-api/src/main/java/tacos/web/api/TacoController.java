package tacos.web.api;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.Ingredient;
import tacos.data.TacoRepository;
import tacos.data.TacoSearchRepository.TacoSearchQuery;
import tacos.web.api.dto.ApiDtos.TacoCatalogResponse;
import tacos.web.api.dto.ApiDtos.TacoClassificationResponse;
import tacos.web.api.dto.ApiDtos.TacoDesignRequest;
import tacos.web.api.dto.ApiDtos.TacoDesignValidationResponse;
import tacos.web.api.dto.ApiDtos.DailyTacoResponse;
import tacos.web.api.dto.ApiDtos.TacoSearchResponse;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.mapper.ApiMapper;
import tacos.web.api.TacoClassificationService.ClassifiedTaco;

@RestController
@RequestMapping(path = "/api/tacos", produces = "application/json")
public class TacoController {
  private TacoRepository tacoRepo;
  private TacoClassificationService classificationService;
  private TacoDesignValidator designValidator;
  private DailyTacoService dailyTacoService;
  private int maxPageSize = 50;

  private static final Set<String> SORT_FIELDS =
      new java.util.LinkedHashSet<>(Arrays.asList("createdAt","name","id"));

  public TacoController(TacoRepository tacoRepo,
      TacoClassificationService classificationService,
      TacoDesignValidator designValidator,
      DailyTacoService dailyTacoService) {
    this.tacoRepo = tacoRepo;
    this.classificationService = classificationService;
    this.designValidator = designValidator;
    this.dailyTacoService = dailyTacoService;
  }

  @GetMapping("/today")
  public Mono<DailyTacoResponse> tacoOfTheDay() {
    return dailyTacoService.recommend().map(ApiMapper::toResponse);
  }

  @Value("${tacocloud.search.max-page-size:50}")
  void configureMaxPageSize(int maxPageSize) {
    this.maxPageSize = maxPageSize;
  }

  @GetMapping
  public Mono<TacoSearchResponse> search(
      @Valid tacos.web.api.dto.ApiDtos.TacoSearchQuery request) {
    TacoSearchQuery query = toSearchQuery(request);
    return tacoRepo.search(query)
        .map(page -> ApiMapper.toResponse(page,classificationService));
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

  private TacoSearchQuery toSearchQuery(
      tacos.web.api.dto.ApiDtos.TacoSearchQuery request) {
    if (request.getSize() > maxPageSize) {
      throw ApiException.unprocessable(
          "PAGE_SIZE_EXCEEDED",
          "Page size must not exceed " + maxPageSize + ".");
    }
    String[] sort = request.getSort() != null
        ? request.getSort().split(",",-1)
        : new String[0];
    if (sort.length != 2 || !SORT_FIELDS.contains(sort[0])) {
      throw ApiException.unprocessable(
          "INVALID_SORT","Sort field is not allowed.");
    }
    String direction = sort[1].toLowerCase(Locale.ROOT);
    if (!"asc".equals(direction) && !"desc".equals(direction)) {
      throw ApiException.unprocessable(
          "INVALID_SORT","Sort direction must be asc or desc.");
    }
    return new TacoSearchQuery(
        normalized(request.getName()),
        normalized(request.getIngredientId()),
        enumValue(Ingredient.DietaryTag.class,request.getDiet(),"INVALID_DIET"),
        enumValue(Ingredient.Allergen.class,request.getExcludeAllergen(),
            "INVALID_ALLERGEN"),
        enumValue(Ingredient.SpiceLevel.class,request.getSpice(),"INVALID_SPICE"),
        request.getPage(),request.getSize(),sort[0],"desc".equals(direction));
  }

  private String normalized(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private <E extends Enum<E>> E enumValue(Class<E> type,String value,
      String errorCode) {
    String normalized = normalized(value);
    if (normalized == null) {
      return null;
    }
    try {
      return Enum.valueOf(type,normalized.toUpperCase(Locale.ROOT));
    }
    catch (IllegalArgumentException exception) {
      throw ApiException.unprocessable(errorCode,"Unsupported filter value.");
    }
  }

}
