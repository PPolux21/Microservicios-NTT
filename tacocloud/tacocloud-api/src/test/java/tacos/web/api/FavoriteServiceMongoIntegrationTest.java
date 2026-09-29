package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Favorite;
import tacos.Ingredient;
import tacos.Ingredient.Allergen;
import tacos.Ingredient.DietaryTag;
import tacos.Ingredient.SpiceLevel;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.User;
import tacos.data.FavoriteRepository;
import tacos.data.IngredientRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;
import tacos.web.api.FavoriteService.FavoritePage;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

@SpringBootTest(
    classes=FavoriteServiceMongoIntegrationTest.TestApplication.class,
    webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={
      "spring.data.mongodb.database=tc21_favorite_test",
      "spring.data.mongodb.auto-index-creation=true"
    })
public class FavoriteServiceMongoIntegrationTest {

  @Autowired
  private FavoriteService service;

  @Autowired
  private FavoriteRepository favoriteRepo;

  @Autowired
  private UserRepository userRepo;

  @Autowired
  private TacoRepository tacoRepo;

  @Autowired
  private IngredientRepository ingredientRepo;

  @Autowired
  private ReactiveMongoTemplate mongo;

  @Test
  public void shouldKeepOneFavoriteForConcurrentAndRepeatedPut() {
    Authentication alice = authentication("alice");

    Mono<Long> scenario = clean()
        .then(seedUser("U-A","alice"))
        .then(seedCatalog("TACO-1"))
        .then(Flux.merge(
            service.addFavorite("TACO-1",alice),
            service.addFavorite("TACO-1",alice)).then())
        .then(service.addFavorite("TACO-1",alice))
        .then(favoriteRepo.countByUserId("U-A"));

    StepVerifier.create(scenario)
        .expectNext(1L)
        .verifyComplete();

    StepVerifier.create(mongo.indexOps(Favorite.class).getIndexInfo()
        .filter(index -> "favorite_user_taco_unique".equals(index.getName()))
        .single())
        .assertNext(index -> assertTrue(index.isUnique()))
        .verifyComplete();
  }

  @Test
  public void shouldIsolateUsersAndNeverDeleteAnotherUsersFavorite() {
    Authentication alice = authentication("alice");
    Authentication bob = authentication("bob");

    Mono<List<Long>> scenario = clean()
        .then(Mono.when(seedUser("U-A","alice"),seedUser("U-B","bob")))
        .then(seedCatalog("TACO-1","TACO-2"))
        .then(service.addFavorite("TACO-1",alice))
        .then(service.addFavorite("TACO-2",bob))
        .then(service.removeFavorite("TACO-2",alice))
        .then(service.listFavorites(alice,0,20))
        .flatMap(page -> {
          assertEquals(Collections.singletonList("TACO-1"),ids(page));
          return service.removeFavorite("TACO-1",alice)
              .then(service.removeFavorite("TACO-1",alice))
              .then(Mono.zip(
                  favoriteRepo.countByUserId("U-A"),
                  favoriteRepo.countByUserId("U-B")))
              .map(counts -> Arrays.asList(counts.getT1(),counts.getT2()));
        });

    StepVerifier.create(scenario)
        .expectNext(Arrays.asList(0L,1L))
        .verifyComplete();
  }

  @Test
  public void shouldPageOnlyAuthenticatedUsersFavorites() {
    Authentication alice = authentication("alice");
    Authentication bob = authentication("bob");
    List<String> tacos = Arrays.asList("T-1","T-2","T-3","T-4","T-5");

    Mono<List<FavoritePage>> scenario = clean()
        .then(Mono.when(seedUser("U-A","alice"),seedUser("U-B","bob")))
        .then(seedCatalog("T-1","T-2","T-3","T-4","T-5","T-B"))
        .thenMany(Flux.fromIterable(tacos)
            .concatMap(id -> service.addFavorite(id,alice)))
        .then(service.addFavorite("T-B",bob))
        .then(Mono.zip(
            service.listFavorites(alice,0,2),
            service.listFavorites(alice,1,2)))
        .map(pages -> Arrays.asList(pages.getT1(),pages.getT2()));

    StepVerifier.create(scenario)
        .assertNext(pages -> {
          assertEquals(2,pages.get(0).getItems().size());
          assertEquals(2,pages.get(1).getItems().size());
          assertEquals(5,pages.get(0).getTotalElements());
          assertEquals(3,pages.get(0).getTotalPages());
          Set<String> returned = new java.util.HashSet<>(ids(pages.get(0)));
          returned.addAll(ids(pages.get(1)));
          assertEquals(4,returned.size());
          assertFalse(returned.contains("T-B"));
        })
        .verifyComplete();
  }

  @Test
  public void shouldRejectMissingTacoWithoutCreatingFavorite() {
    Authentication alice = authentication("alice");

    Mono<Void> scenario = clean()
        .then(seedUser("U-A","alice"))
        .then(service.addFavorite("DOES_NOT_EXIST",alice));

    StepVerifier.create(scenario)
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof ApiException);
          assertEquals("TACO_NOT_FOUND",((ApiException) error).getCode());
        })
        .verify();

    StepVerifier.create(favoriteRepo.countByUserId("U-A"))
        .expectNext(0L)
        .verifyComplete();
  }

  @Test
  public void shouldDeleteAndHideOrphanWhenTacoNoLongerExists() {
    Authentication alice = authentication("alice");

    Mono<FavoritePage> scenario = clean()
        .then(seedUser("U-A","alice"))
        .then(seedCatalog("TACO-1"))
        .then(service.addFavorite("TACO-1",alice))
        .then(tacoRepo.deleteById("TACO-1"))
        .then(service.listFavorites(alice,0,20));

    StepVerifier.create(scenario)
        .assertNext(page -> {
          assertTrue(page.getItems().isEmpty());
          assertEquals(0,page.getTotalElements());
        })
        .verifyComplete();

    StepVerifier.create(favoriteRepo.countByUserId("U-A"))
        .expectNext(0L)
        .verifyComplete();
  }

  private Mono<Void> clean() {
    return mongo.remove(new Query(),Favorite.class)
        .then(mongo.remove(new Query(),Taco.class))
        .then(mongo.remove(new Query(),Ingredient.class))
        .then(mongo.remove(new Query(),User.class))
        .then();
  }

  private Mono<User> seedUser(String id,String username) {
    User user = new User(
        username,"{noop}password",username,"street","city","state",
        "00000","000-000-0000",username + "@example.test");
    user.setId(id);
    return userRepo.save(user);
  }

  private Mono<Void> seedCatalog(String... tacoIds) {
    Ingredient base = ingredient("BASE",Type.WRAP);
    Ingredient filling = ingredient("FILL",Type.VEGGIES);
    return Mono.when(ingredientRepo.save(base),ingredientRepo.save(filling))
        .thenMany(Flux.fromArray(tacoIds)
            .concatMap(id -> tacoRepo.save(taco(id,base,filling))))
        .then();
  }

  private Ingredient ingredient(String id,Type type) {
    Ingredient ingredient = new Ingredient(
        id,id,type,new BigDecimal("1.00"),true,100,10);
    ingredient.setDietaryTags(EnumSet.allOf(DietaryTag.class));
    ingredient.setAllergens(EnumSet.noneOf(Allergen.class));
    ingredient.setSpiceLevel(SpiceLevel.NONE);
    return ingredient;
  }

  private Taco taco(String id,Ingredient... ingredients) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName("Favorite " + id);
    taco.setIngredients(Arrays.asList(ingredients));
    return taco;
  }

  private Authentication authentication(String username) {
    return new UsernamePasswordAuthenticationToken(
        username,"password",AuthorityUtils.createAuthorityList("ROLE_USER"));
  }

  private List<String> ids(FavoritePage page) {
    return page.getItems().stream()
        .map(classified -> classified.getTaco().getId())
        .collect(Collectors.toList());
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableReactiveMongoRepositories(basePackageClasses=FavoriteRepository.class)
  @Import({FavoriteService.class,TacoClassificationService.class})
  static class TestApplication {
  }
}
