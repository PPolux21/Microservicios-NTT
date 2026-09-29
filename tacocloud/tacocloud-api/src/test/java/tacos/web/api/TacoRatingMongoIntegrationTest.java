package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Allergen;
import tacos.Ingredient.DietaryTag;
import tacos.Ingredient.SpiceLevel;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.TacoRating;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.TacoRatingRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;
import tacos.web.api.TacoRatingService.RankedTaco;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

@SpringBootTest(
    classes=TacoRatingMongoIntegrationTest.TestApplication.class,
    webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={
      "spring.data.mongodb.database=tc22_rating_test",
      "spring.data.mongodb.auto-index-creation=true",
      "tacocloud.rating.min-votes=3"
    })
public class TacoRatingMongoIntegrationTest {

  @Autowired private TacoRatingService service;
  @Autowired private TacoRatingRepository ratingRepo;
  @Autowired private TacoRepository tacoRepo;
  @Autowired private IngredientRepository ingredientRepo;
  @Autowired private UserRepository userRepo;
  @Autowired private ReactiveMongoTemplate mongo;

  @Test
  public void shouldAtomicallyUpsertOneRatingAndUpdateItsScore() {
    Authentication alice = authentication("alice");
    Mono<TacoRating> scenario = clean()
        .then(seedUser("U-A","alice"))
        .then(seedTaco("TACO-1",true))
        .then(Mono.when(
            service.rate("TACO-1",4,alice),
            service.rate("TACO-1",4,alice)))
        .then(service.rate("TACO-1",2,alice))
        .then(service.rate("TACO-1",5,alice))
        .then(ratingRepo.findByUserIdAndTacoId("U-A","TACO-1"));

    StepVerifier.create(scenario)
        .assertNext(rating -> assertEquals(5,rating.getScore()))
        .verifyComplete();
    StepVerifier.create(ratingRepo.countByUserIdAndTacoId("U-A","TACO-1"))
        .expectNext(1L).verifyComplete();
    StepVerifier.create(mongo.indexOps(TacoRating.class).getIndexInfo()
        .filter(index -> "rating_user_taco_unique".equals(index.getName()))
        .single())
        .assertNext(index -> assertTrue(index.isUnique()))
        .verifyComplete();

    service.configureMinimumVotes(1);
    StepVerifier.create(service.top(10))
        .assertNext(top -> {
          assertEquals(1,top.size());
          assertEquals(new BigDecimal("5.00"),top.get(0).getAverage());
          assertEquals(1,top.get(0).getCount());
        }).verifyComplete();
  }

  @Test
  public void shouldNeverOverwriteAnotherUsersRating() {
    Authentication alice = authentication("alice");
    Authentication bob = authentication("bob");
    Mono<List<TacoRating>> scenario = clean()
        .then(Mono.when(seedUser("U-A","alice"),seedUser("U-B","bob")))
        .then(seedTaco("TACO-1",true))
        .then(service.rate("TACO-1",2,alice))
        .then(service.rate("TACO-1",5,bob))
        .then(service.rate("TACO-1",4,alice))
        .then(ratingRepo.findAll().collectList());

    StepVerifier.create(scenario)
        .assertNext(ratings -> {
          assertEquals(2,ratings.size());
          Map<String,Integer> scores = ratings.stream().collect(
              Collectors.toMap(TacoRating::getUserId,TacoRating::getScore));
          assertEquals(Integer.valueOf(4),scores.get("U-A"));
          assertEquals(Integer.valueOf(5),scores.get("U-B"));
        }).verifyComplete();

    service.configureMinimumVotes(1);
    StepVerifier.create(service.top(10))
        .assertNext(top -> {
          assertEquals(1,top.size());
          assertEquals(new BigDecimal("4.50"),top.get(0).getAverage());
          assertEquals(2,top.get(0).getCount());
        }).verifyComplete();
  }

  @Test
  public void shouldAggregateDistributionMinimumVotesAndEveryTieBreaker() {
    Mono<List<RankedTaco>> scenario = clean()
        .then(seedTacos(
            taco("T-A",true),taco("T-B",true),taco("T-C",true),
            taco("T-D",true),taco("T-E",true),taco("T-X",false)))
        .then(seedRatings("T-A",5))
        .then(seedRatings("T-B",4,4,4,4))
        .then(seedRatings("T-C",5,4,3))
        .then(seedRatings("T-D",4,4,4))
        .then(seedRatings("T-E",5,4,4))
        .then(seedRatings("T-X",5,5,5,5,5))
        .then(service.top(10));

    StepVerifier.create(scenario)
        .assertNext(top -> {
          assertEquals(Arrays.asList("T-E","T-B","T-C","T-D"),ids(top));
          assertFalse(ids(top).contains("T-A"));
          assertFalse(ids(top).contains("T-X"));
          RankedTaco rounded = top.get(0);
          assertEquals(new BigDecimal("4.33"),rounded.getAverage());
          assertEquals(3,rounded.getCount());
          assertEquals(Long.valueOf(0),rounded.getDistribution().get(1));
          assertEquals(Long.valueOf(2),rounded.getDistribution().get(4));
          assertEquals(Long.valueOf(1),rounded.getDistribution().get(5));
          RankedTaco exactAverage = top.get(2);
          assertEquals(new BigDecimal("4.00"),exactAverage.getAverage());
          assertEquals(3,exactAverage.getCount());
        }).verifyComplete();

    StepVerifier.create(service.top(2))
        .assertNext(top -> assertEquals(
            Arrays.asList("T-E","T-B"),ids(top)))
        .verifyComplete();
  }

  @Test
  public void shouldUseOneMongoPipelineWithLookupInsteadOfNPlusOne() {
    try {
      Aggregation aggregation = TacoRatingRepository.class
          .getMethod("findTopRatings",long.class,long.class)
          .getAnnotation(Aggregation.class);
      String pipeline = String.join(" ",aggregation.pipeline());
      assertTrue(pipeline.contains("'$group'"));
      assertTrue(pipeline.contains("'$lookup'"));
      assertTrue(pipeline.contains("'$sort'"));
      assertTrue(pipeline.contains("'$limit'"));
    }
    catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  @Test
  public void shouldRejectMissingAndUnpublishedTacosWithoutRating() {
    Authentication alice = authentication("alice");
    Mono<Void> setup = clean()
        .then(seedUser("U-A","alice"))
        .then(seedTaco("HIDDEN",false))
        .then();
    StepVerifier.create(setup).verifyComplete();

    StepVerifier.create(service.rate("DOES_NOT_EXIST",4,alice))
        .expectErrorSatisfies(error -> assertApiCode(error,"TACO_NOT_FOUND"))
        .verify();
    StepVerifier.create(service.rate("HIDDEN",4,alice))
        .expectErrorSatisfies(error -> assertApiCode(error,"TACO_NOT_PUBLISHED"))
        .verify();
    StepVerifier.create(ratingRepo.count())
        .expectNext(0L).verifyComplete();
  }

  private Mono<Void> clean() {
    service.configureMinimumVotes(3);
    return mongo.remove(new Query(),TacoRating.class)
        .then(mongo.remove(new Query(),Taco.class))
        .then(mongo.remove(new Query(),Ingredient.class))
        .then(mongo.remove(new Query(),User.class)).then();
  }

  private Mono<User> seedUser(String id,String username) {
    User user = new User(username,"{noop}password",username,"street","city",
        "state","00000","000-000-0000",username + "@example.test");
    user.setId(id);
    return userRepo.save(user);
  }

  private Mono<Taco> seedTaco(String id,boolean published) {
    return seedTacos(taco(id,published)).then(tacoRepo.findById(id));
  }

  private Mono<Void> seedTacos(Taco... tacos) {
    Ingredient ingredient = ingredient();
    for (Taco taco : tacos) {
      taco.setIngredients(Collections.singletonList(ingredient));
    }
    return ingredientRepo.save(ingredient)
        .thenMany(Flux.fromArray(tacos).concatMap(tacoRepo::save)).then();
  }

  private Mono<Void> seedRatings(String tacoId,int... scores) {
    List<TacoRating> ratings = new ArrayList<>();
    for (int index = 0; index < scores.length; index++) {
      ratings.add(new TacoRating(
          tacoId + "-USER-" + index,tacoId,scores[index]));
    }
    return ratingRepo.saveAll(ratings).then();
  }

  private Taco taco(String id,boolean published) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName("Rated " + id);
    taco.setPublished(published);
    return taco;
  }

  private Ingredient ingredient() {
    Ingredient ingredient = new Ingredient(
        "BASE","Base",Type.WRAP,new BigDecimal("1.00"),true,100,10);
    ingredient.setDietaryTags(EnumSet.allOf(DietaryTag.class));
    ingredient.setAllergens(EnumSet.noneOf(Allergen.class));
    ingredient.setSpiceLevel(SpiceLevel.NONE);
    return ingredient;
  }

  private Authentication authentication(String username) {
    return new UsernamePasswordAuthenticationToken(
        username,"password",AuthorityUtils.createAuthorityList("ROLE_USER"));
  }

  private List<String> ids(List<RankedTaco> top) {
    return top.stream().map(item -> item.getTaco().getTaco().getId())
        .collect(Collectors.toList());
  }

  private void assertApiCode(Throwable error,String code) {
    assertTrue(error instanceof ApiException);
    assertEquals(code,((ApiException) error).getCode());
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableReactiveMongoRepositories(basePackageClasses=TacoRatingRepository.class)
  @Import({TacoRatingService.class,TacoClassificationService.class})
  static class TestApplication {
  }
}
