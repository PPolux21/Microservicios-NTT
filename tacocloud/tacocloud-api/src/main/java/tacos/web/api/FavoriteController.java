package tacos.web.api;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.web.api.dto.ApiDtos.FavoritePageResponse;
import tacos.web.api.dto.ApiDtos.FavoriteQuery;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.mapper.ApiMapper;

@RestController
@RequestMapping(
    path={"/api/users/me/favorites","/api/v1/users/me/favorites"},
    produces="application/json")
public class FavoriteController {

  private final FavoriteService favoriteService;
  private int maxPageSize = 50;

  public FavoriteController(FavoriteService favoriteService) {
    this.favoriteService = favoriteService;
  }

  @Value("${tacocloud.search.max-page-size:50}")
  void configureMaxPageSize(int maxPageSize) {
    this.maxPageSize = maxPageSize;
  }

  @PutMapping("/{tacoId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> add(@PathVariable String tacoId,
      Authentication authentication) {
    return favoriteService.addFavorite(tacoId,authentication);
  }

  @DeleteMapping("/{tacoId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> remove(@PathVariable String tacoId,
      Authentication authentication) {
    return favoriteService.removeFavorite(tacoId,authentication);
  }

  @GetMapping
  public Mono<FavoritePageResponse> list(@Valid FavoriteQuery query,
      Authentication authentication) {
    if (query.getSize() > maxPageSize) {
      throw ApiException.unprocessable(
          "PAGE_SIZE_EXCEEDED",
          "Page size must not exceed " + maxPageSize + ".");
    }
    return favoriteService.listFavorites(
        authentication,query.getPage(),query.getSize())
        .map(ApiMapper::toResponse);
  }
}
