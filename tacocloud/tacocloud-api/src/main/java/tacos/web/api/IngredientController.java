package tacos.web.api;

import java.net.URI;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServerHttpRequest;
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
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.data.IngredientRepository;

@RestController
@RequestMapping(path="/api/ingredients", produces="application/json")
@CrossOrigin(origins="http://localhost:8080")
public class IngredientController {

  private IngredientRepository repo;

  @Autowired
  public IngredientController(IngredientRepository repo) {
    this.repo = repo;
  }

  @GetMapping
  public Flux<Ingredient> allIngredients() {
    return repo.findAll();
  }

  @GetMapping("/{id}")
  public Mono<Ingredient> byId(@PathVariable String id) {
    return repo.findById(id);
  }

  @PutMapping("/{id}")
  public Mono<ResponseEntity<Ingredient>> updateIngredient(@PathVariable String id, @RequestBody Ingredient ingredient) {
    if (!ingredient.getId().equals(id)) {
      return Mono.just(ResponseEntity.badRequest().build()); // 400 Bad Request
    }

    return repo.findById(id)
      .flatMap(repoFound -> {
        return repo.save(ingredient)
          .map(savedIngredient -> ResponseEntity.ok(savedIngredient)); // 200 OK
      })
      .defaultIfEmpty(ResponseEntity.notFound().build()); // 404 Not Found
  }

  @PostMapping
  public Mono<ResponseEntity<Ingredient>> postIngredient(@Valid @RequestBody Ingredient ingredient, HttpServletRequest request) {

    return repo.save(ingredient)
      .map(savedIngredient -> {
        URI location = ServletUriComponentsBuilder
          .fromRequestUri(request)
          .pathSegment(savedIngredient.getId())
          .build()
          .toUri();
        return ResponseEntity
          .created(location)
          .body(savedIngredient);
      });
  }

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
