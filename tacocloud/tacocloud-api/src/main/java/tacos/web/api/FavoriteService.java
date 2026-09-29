package tacos.web.api;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import lombok.Data;
import reactor.core.publisher.Mono;
import tacos.Favorite;
import tacos.User;
import tacos.data.FavoriteRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;
import tacos.web.api.TacoClassificationService.ClassifiedTaco;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

@Service
public class FavoriteService {

  private final FavoriteRepository favoriteRepo;
  private final UserRepository userRepo;
  private final TacoRepository tacoRepo;
  private final TacoClassificationService classificationService;

  public FavoriteService(FavoriteRepository favoriteRepo,
      UserRepository userRepo,TacoRepository tacoRepo,
      TacoClassificationService classificationService) {
    this.favoriteRepo = favoriteRepo;
    this.userRepo = userRepo;
    this.tacoRepo = tacoRepo;
    this.classificationService = classificationService;
  }

  public Mono<Void> addFavorite(String tacoId,
      Authentication authentication) {
    return currentUser(authentication)
        .flatMap(user -> tacoRepo.existsById(tacoId)
            .flatMap(exists -> exists
                ? favoriteRepo.save(new Favorite(user.getId(),tacoId)).then()
                : Mono.error(ApiException.notFound(
                    "TACO_NOT_FOUND","Taco does not exist."))))
        .onErrorResume(DuplicateKeyException.class,error -> Mono.empty());
  }

  public Mono<Void> removeFavorite(String tacoId,
      Authentication authentication) {
    return currentUser(authentication)
        .flatMap(user -> favoriteRepo
            .deleteByUserIdAndTacoId(user.getId(),tacoId))
        .then();
  }

  public Mono<FavoritePage> listFavorites(Authentication authentication,
      int page,int size) {
    return currentUser(authentication)
        .flatMap(user -> listFavorites(user.getId(),page,size));
  }

  private Mono<FavoritePage> listFavorites(String userId,int page,int size) {
    Sort sort = Sort.by(Sort.Direction.DESC,"createdAt")
        .and(Sort.by(Sort.Direction.ASC,"id"));
    Pageable pageable = PageRequest.of(page,size,sort);

    Mono<List<ClassifiedTaco>> items = favoriteRepo
        .findByUserId(userId,pageable)
        .concatMap(favorite -> tacoRepo.findById(favorite.getTacoId())
            .flatMap(classificationService::classify)
            .switchIfEmpty(favoriteRepo.delete(favorite).then(Mono.empty())))
        .collectList();

    return items.flatMap(pageItems -> favoriteRepo.countByUserId(userId)
        .map(total -> new FavoritePage(
            pageItems,page,size,total)));
  }

  private Mono<User> currentUser(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return Mono.error(new ApiException(
          HttpStatus.UNAUTHORIZED,"AUTHENTICATION_REQUIRED",
          "Authentication is required."));
    }
    return userRepo.findByUsername(authentication.getName())
        .switchIfEmpty(Mono.error(ApiException.notFound(
            "USER_NOT_FOUND","Authenticated user does not exist.")));
  }

  @Data
  @AllArgsConstructor
  public static class FavoritePage {
    private List<ClassifiedTaco> items;
    private int page;
    private int size;
    private long totalElements;

    public int getTotalPages() {
      return size == 0
          ? 0
          : (int) ((totalElements + size - 1) / size);
    }
  }
}
