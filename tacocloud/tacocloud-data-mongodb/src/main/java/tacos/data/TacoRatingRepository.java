package tacos.data;

import java.util.List;

import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.TacoRating;

public interface TacoRatingRepository
    extends ReactiveCrudRepository<TacoRating,String> {

  Mono<TacoRating> findByUserIdAndTacoId(String userId,String tacoId);

  Mono<Long> countByUserIdAndTacoId(String userId,String tacoId);

  @Aggregation(pipeline={
    "{ '$group': { '_id': { 'tacoId': '$tacoId', 'score': '$score' }, "
        + "'scoreCount': { '$sum': 1 } } }",
    "{ '$group': { '_id': '$_id.tacoId', "
        + "'voteCount': { '$sum': '$scoreCount' }, "
        + "'scoreTotal': { '$sum': { '$multiply': "
        + "['$_id.score', '$scoreCount'] } }, "
        + "'distribution': { '$push': { 'score': '$_id.score', "
        + "'count': '$scoreCount' } } } }",
    "{ '$match': { 'voteCount': { '$gte': ?0 } } }",
    "{ '$lookup': { 'from': 'taco', 'localField': '_id', "
        + "'foreignField': '_id', 'as': 'taco' } }",
    "{ '$unwind': '$taco' }",
    "{ '$match': { 'taco.published': { '$ne': false } } }",
    "{ '$addFields': { 'averageForSort': { '$divide': "
        + "['$scoreTotal', '$voteCount'] } } }",
    "{ '$sort': { 'averageForSort': -1, 'voteCount': -1, '_id': 1 } }",
    "{ '$limit': ?1 }",
    "{ '$project': { '_id': 0, 'tacoId': '$_id', 'taco': 1, "
        + "'scoreTotal': 1, 'voteCount': 1, 'distribution': 1 } }"
  })
  Flux<RatingAggregate> findTopRatings(long minimumVotes,long limit);

  @Data
  @NoArgsConstructor
  class RatingAggregate {
    private String tacoId;
    private Taco taco;
    private long scoreTotal;
    private long voteCount;
    private List<ScoreCount> distribution;
  }

  @Data
  @NoArgsConstructor
  class ScoreCount {
    private int score;
    private long count;
  }
}
