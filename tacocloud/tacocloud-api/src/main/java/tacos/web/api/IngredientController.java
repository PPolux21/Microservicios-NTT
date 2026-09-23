package tacos.web.api;

import java.net.URI;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
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
import tacos.web.api.mapper.ApiMapper;
import tacos.data.IngredientRepository;

@RestController
@RequestMapping(path="/api/ingredients", produces="application/json")
@CrossOrigin(origins="http://localhost:8080")
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
    return repo.findById(id).map(ApiMapper::toResponse);
  }
  
  /*
  * Mantiene 200 / 400 / 404
  * definidos en TC-01.
  */
 @PutMapping("/{id}")
 public Mono<ResponseEntity<IngredientResponse>> updateIngredient(@PathVariable String id, @RequestBody IngredientRequest request) {
    if (request.getId() == null || !request.getId().equals(id)) {
      return Mono.just(ResponseEntity.badRequest().build());
    }
    Ingredient ingredient = ApiMapper.toEntity(request);
    
    return repo.findById(id)
    .flatMap(found ->
      repo.save(ingredient)
      .map(savedIngredient ->
        ResponseEntity.ok(ApiMapper.toResponse(savedIngredient))))
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }
    
  /*
  * Mantiene la construcción dinámica
  * de Location implementada en TC-03.
  */
  @PostMapping
  public Mono<ResponseEntity<IngredientResponse>> postIngredient(@RequestBody Mono<IngredientRequest> ingredient, HttpServletRequest request) {

    return ingredient.map(ApiMapper::toEntity)
      .flatMap(repo::save)
      .map(savedIngredient -> {
        URI location = ServletUriComponentsBuilder
          .fromRequestUri(request)
          .pathSegment(savedIngredient.getId())
          .build()
          .toUri();
        return ResponseEntity
          .created(location)
          .body(ApiMapper.toResponse(savedIngredient));
      });
  }

  /*
   * TC-02 no necesita DTO porque
   * DELETE no recibe ni devuelve
   * una entidad.
   */
  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<Void>> deleteIngredient(@PathVariable String id) {
    return repo.findById(id)
        .flatMap(repoFound -> {
            return repo.deleteById(id)
                .thenReturn(ResponseEntity.noContent().<Void>build()); // 204 No Content
              })
        .defaultIfEmpty(ResponseEntity.notFound().<Void>build()); // 404 Not Found
  }

}
