package tacos.web.api;

import java.net.URI;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.web.api.dto.ApiDtos.IngredientRequest;
import tacos.web.api.dto.ApiDtos.IngredientResponse;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.mapper.ApiMapper;
import tacos.data.IngredientRepository;

@RestController
@RequestMapping(path="/api/ingredients", produces="application/json")
public class IngredientController {

  private IngredientRepository repo;

  public IngredientController(IngredientRepository repo) {
    this.repo = repo;
  }

  @GetMapping
  public Flux<IngredientResponse> allIngredients() {
    return repo.findAll().map(ApiMapper::toResponse);
  }

  @GetMapping("/{id}")
  public Mono<IngredientResponse> byId(@PathVariable String id) {

    return repo.findById(id)
      .switchIfEmpty(
          Mono.error(
              ApiException.notFound("INGREDIENT_NOT_FOUND","Ingredient does not exist.")))
      .map(ApiMapper::toResponse);
  }
  
  @PutMapping("/{id}")
  public Mono<ResponseEntity<IngredientResponse>>updateIngredient(@PathVariable String id,
          @Valid @RequestBody IngredientRequest request) {

    if (!request.getId().equals(id)) {
      return Mono.error(
        ApiException.badRequest("INGREDIENT_ID_MISMATCH","Path ID and body ID must match."));
    }

    Ingredient ingredient = ApiMapper.toEntity(request);

    return repo.findById(id)
      .switchIfEmpty(
        Mono.error(
          ApiException.notFound("INGREDIENT_NOT_FOUND","Ingredient does not exist.")))
      .flatMap(found ->repo.save(ingredient))
      .map(saved ->ResponseEntity.ok(ApiMapper.toResponse(saved)));
  }
    
  @PostMapping
  public Mono<ResponseEntity<IngredientResponse>> postIngredient(@Valid @RequestBody IngredientRequest request,
          HttpServletRequest servletRequest) {

    Ingredient ingredient = ApiMapper.toEntity(request);

    return repo.save(ingredient)
      .map(savedIngredient -> {
        URI location =
          ServletUriComponentsBuilder
            .fromRequestUri(servletRequest)
            .pathSegment(savedIngredient.getId())
            .build()
            .toUri();
        return ResponseEntity
          .created(location)
          .body(ApiMapper.toResponse(savedIngredient));
      });
  }

  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<Void>> deleteIngredient(@PathVariable String id) {

    return repo.findById(id)
      .switchIfEmpty(
        Mono.error(
          ApiException.notFound("INGREDIENT_NOT_FOUND","Ingredient does not exist.")))
      .flatMap(found ->
        repo.deleteById(id)
          .thenReturn(ResponseEntity.noContent().<Void>build()));
  }

}
