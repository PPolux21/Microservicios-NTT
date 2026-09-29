package tacos.web.api;

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
import tacos.data.TacoRepository;
import tacos.web.api.dto.ApiDtos.TacoCatalogResponse;
import tacos.web.api.dto.ApiDtos.TacoClassificationResponse;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.mapper.ApiMapper;

@RestController
@RequestMapping(path = "/api/tacos", produces = "application/json")
public class TacoController {
  private TacoRepository tacoRepo;
  private TacoClassificationService classificationService;

  public TacoController(TacoRepository tacoRepo,
      TacoClassificationService classificationService) {
    this.tacoRepo = tacoRepo;
    this.classificationService = classificationService;
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
    return tacoRepo.save(taco)
        .flatMap(classificationService::classify)
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

}
