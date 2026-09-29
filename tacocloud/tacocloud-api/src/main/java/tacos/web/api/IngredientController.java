package tacos.web.api;

import java.net.URI;
import java.util.Objects;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import tacos.web.api.dto.ApiDtos.IngredientAdminResponse;
import tacos.web.api.dto.ApiDtos.IngredientCatalogUpdateRequest;
import tacos.web.api.dto.ApiDtos.IngredientResponse;
import tacos.web.api.dto.ApiDtos.StockAdjustmentRequest;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.mapper.ApiMapper;
import tacos.data.IngredientRepository;

@RestController
@RequestMapping(path="/api", produces="application/json")
public class IngredientController {

  private IngredientRepository repo;

  public IngredientController(IngredientRepository repo) {
    this.repo = repo;
  }

  @GetMapping("/ingredients")
  public Flux<IngredientResponse> allIngredients() {
    return repo.findAll().map(ApiMapper::toResponse);
  }

  @GetMapping("/ingredients/{id}")
  public Mono<IngredientResponse> byId(@PathVariable String id) {

    return repo.findById(id)
      .switchIfEmpty(
          Mono.error(
              ApiException.notFound("INGREDIENT_NOT_FOUND","Ingredient does not exist.")))
      .map(ApiMapper::toResponse);
  }
  
  @PutMapping("/ingredients/{id}")
  public Mono<ResponseEntity<IngredientResponse>>updateIngredient(@PathVariable String id,
          @Valid @RequestBody IngredientRequest request) {

    if (!request.getId().equals(id)) {
      return Mono.error(
        ApiException.badRequest("INGREDIENT_ID_MISMATCH","Path ID and body ID must match."));
    }

    return repo.findById(id)
      .switchIfEmpty(
        Mono.error(
          ApiException.notFound("INGREDIENT_NOT_FOUND","Ingredient does not exist.")))
      .flatMap(found -> {
        Ingredient requested = ApiMapper.toEntity(request);
        found.setName(request.getName());
        found.setType(request.getType());
        found.setDietaryTags(requested.getDietaryTags());
        found.setAllergens(requested.getAllergens());
        found.setSpiceLevel(requested.getSpiceLevel());
        return repo.save(found);
      })
      .map(saved ->ResponseEntity.ok(ApiMapper.toResponse(saved)));
  }
    
  @PostMapping("/ingredients")
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

  @DeleteMapping("/ingredients/{id}")
  public Mono<ResponseEntity<Void>> deleteIngredient(@PathVariable String id) {

    return repo.findById(id)
      .switchIfEmpty(
        Mono.error(
          ApiException.notFound("INGREDIENT_NOT_FOUND","Ingredient does not exist.")))
      .flatMap(found ->
        repo.deleteById(id)
          .thenReturn(ResponseEntity.noContent().<Void>build()));
  }

  @PatchMapping("/admin/ingredients/{id}/catalog")
  public Mono<IngredientAdminResponse> updateCatalog(
      @PathVariable String id,
      @Valid @RequestBody IngredientCatalogUpdateRequest request,
      Authentication authentication) {

    if (!isAdmin(authentication)) {
      return Mono.error(
          ApiException.forbidden("ADMIN_REQUIRED","Only ADMIN can modify the catalog."));
    }

    if (!request.hasChanges()) {
      return Mono.error(
          ApiException.badRequest("CATALOG_UPDATE_EMPTY","At least one catalog field is required."));
    }

    return repo.findById(id)
      .switchIfEmpty(
          Mono.error(
              ApiException.notFound("INGREDIENT_NOT_FOUND","Ingredient does not exist.")))
      .flatMap(ingredient -> {
        verifyVersion(ingredient,request.getExpectedVersion());

        if (request.getUnitPrice() != null) {
          ingredient.setUnitPrice(request.getUnitPrice());
        }
        if (request.getAvailable() != null) {
          ingredient.setAvailable(request.getAvailable());
        }
        if (request.getReorderLevel() != null) {
          ingredient.setReorderLevel(request.getReorderLevel());
        }

        verifyCatalogState(ingredient);
        return repo.save(ingredient);
      })
      .onErrorMap(
          OptimisticLockingFailureException.class,
          error -> ApiException.conflict(
              "INGREDIENT_VERSION_CONFLICT","Ingredient was modified by another request."))
      .map(ApiMapper::toAdminResponse);
  }

  @PostMapping("/admin/ingredients/{id}/stock-adjustments")
  public Mono<IngredientAdminResponse> adjustStock(
      @PathVariable String id,
      @Valid @RequestBody StockAdjustmentRequest request,
      Authentication authentication) {

    if (!isAdmin(authentication)) {
      return Mono.error(
          ApiException.forbidden("ADMIN_REQUIRED","Only ADMIN can modify stock."));
    }

    return repo.findById(id)
      .switchIfEmpty(
          Mono.error(
              ApiException.notFound("INGREDIENT_NOT_FOUND","Ingredient does not exist.")))
      .flatMap(ingredient -> {
        verifyVersion(ingredient,request.getExpectedVersion());

        long adjustedStock = (long) ingredient.getStockOnHand()
            + request.getQuantity();

        if (adjustedStock < 0 || adjustedStock > Integer.MAX_VALUE) {
          return Mono.error(
              ApiException.unprocessable(
                  "INVALID_STOCK_ADJUSTMENT",
                  "Stock adjustment must produce a non-negative integer."));
        }

        ingredient.setStockOnHand((int) adjustedStock);
        if (adjustedStock == 0) {
          ingredient.setAvailable(false);
        }

        return repo.save(ingredient);
      })
      .onErrorMap(
          OptimisticLockingFailureException.class,
          error -> ApiException.conflict(
              "INGREDIENT_VERSION_CONFLICT","Ingredient was modified by another request."))
      .map(ApiMapper::toAdminResponse);
  }

  private void verifyVersion(Ingredient ingredient,Long expectedVersion) {
    Long currentVersion = ingredient.getVersion() != null
        ? ingredient.getVersion()
        : 0L;

    if (!Objects.equals(currentVersion,expectedVersion)) {
      throw ApiException.conflict(
          "INGREDIENT_VERSION_CONFLICT","Ingredient was modified by another request.");
    }
  }

  private void verifyCatalogState(Ingredient ingredient) {
    if (ingredient.getUnitPrice() == null
        || ingredient.getUnitPrice().signum() < 0
        || ingredient.getStockOnHand() < 0
        || ingredient.getReorderLevel() < 0
        || !ingredient.isCatalogStateValid()) {
      throw ApiException.unprocessable(
          "INVALID_CATALOG_STATE","Catalog values must be non-negative and available ingredients must have stock.");
    }
  }

  private boolean isAdmin(Authentication authentication) {
    return authentication != null
        && authentication.getAuthorities() != null
        && authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
  }

}
