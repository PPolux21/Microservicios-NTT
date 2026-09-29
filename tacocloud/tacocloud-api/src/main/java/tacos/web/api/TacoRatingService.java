package tacos.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import lombok.Data;
import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.TacoRating;
import tacos.User;
import tacos.data.TacoRatingRepository;
import tacos.data.TacoRatingRepository.RatingAggregate;
import tacos.data.TacoRatingRepository.ScoreCount;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;
import tacos.web.api.TacoClassificationService.ClassifiedTaco;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

@Service
public class TacoRatingService {

  public static final int AVERAGE_SCALE = 2;
  public static final RoundingMode AVERAGE_ROUNDING = RoundingMode.HALF_UP;

  private final TacoRatingRepository ratingRepo;
  private final TacoRepository tacoRepo;
  private final UserRepository userRepo;
  private final ReactiveMongoTemplate mongo;
  private final TacoClassificationService classificationService;
  private int minimumVotes;

  public TacoRatingService(TacoRatingRepository ratingRepo,
      TacoRepository tacoRepo,UserRepository userRepo,
      ReactiveMongoTemplate mongo,
      TacoClassificationService classificationService) {
    this.ratingRepo = ratingRepo;
    this.tacoRepo = tacoRepo;
    this.userRepo = userRepo;
    this.mongo = mongo;
    this.classificationService = classificationService;
  }

  @Value("${tacocloud.rating.min-votes}")
  void configureMinimumVotes(int minimumVotes) {
    this.minimumVotes = minimumVotes;
  }

  public Mono<Void> rate(String tacoId,int score,
      Authentication authentication) {
    return currentUser(authentication)
        .flatMap(user -> tacoRepo.findById(tacoId)
            .switchIfEmpty(Mono.error(ApiException.notFound(
                "TACO_NOT_FOUND","Taco does not exist.")))
            .flatMap(taco -> ensurePublished(taco)
                .then(upsert(user.getId(),tacoId,score))));
  }

  public Mono<List<RankedTaco>> top(int limit) {
    return ratingRepo.findTopRatings(minimumVotes,limit)
        .map(this::toRankedTaco)
        .collectList();
  }

  private Mono<Void> upsert(String userId,String tacoId,int score) {
    Query query = Query.query(Criteria.where("userId").is(userId)
        .and("tacoId").is(tacoId));
    Update update = new Update()
        .setOnInsert("userId",userId)
        .setOnInsert("tacoId",tacoId)
        .set("score",score);
    return mongo.upsert(query,update,TacoRating.class)
        .onErrorResume(DuplicateKeyException.class,
            error -> mongo.updateFirst(
                query,Update.update("score",score),TacoRating.class))
        .then();
  }

  private Mono<Void> ensurePublished(Taco taco) {
    return taco.isPublished()
        ? Mono.empty()
        : Mono.error(ApiException.unprocessable(
            "TACO_NOT_PUBLISHED","Taco is not published."));
  }

  private RankedTaco toRankedTaco(RatingAggregate aggregate) {
    Taco taco = aggregate.getTaco();
    List<tacos.Ingredient> ingredients = taco.getIngredients() != null
        ? taco.getIngredients()
        : java.util.Collections.emptyList();
    ClassifiedTaco classified = new ClassifiedTaco(
        taco,ingredients,classificationService.classifyIngredients(ingredients));

    BigDecimal average = BigDecimal.valueOf(aggregate.getScoreTotal())
        .divide(BigDecimal.valueOf(aggregate.getVoteCount()),
            AVERAGE_SCALE,AVERAGE_ROUNDING);
    Map<Integer,Long> distribution = new LinkedHashMap<>();
    for (int score = 1; score <= 5; score++) {
      distribution.put(score,0L);
    }
    if (aggregate.getDistribution() != null) {
      Map<Integer,Long> counts = aggregate.getDistribution().stream()
          .collect(Collectors.toMap(ScoreCount::getScore,ScoreCount::getCount));
      distribution.replaceAll((score,count) -> counts.getOrDefault(score,0L));
    }
    return new RankedTaco(
        classified,average,aggregate.getVoteCount(),distribution);
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
  public static class RankedTaco {
    private ClassifiedTaco taco;
    private BigDecimal average;
    private long count;
    private Map<Integer,Long> distribution;
  }
}
