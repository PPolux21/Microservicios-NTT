package tacos.data;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.mongodb.repository.Query;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.TacoOrder;

public interface OrderRepository 
         extends ReactiveCrudRepository<TacoOrder, String> {

  @Query("{ '$or': [ { 'userId': ?0 }, { 'user._id': ?0 } ] }")
  Flux<TacoOrder> findByUserId(String userId, Pageable pageable);

  @Query(value="{ '$or': [ { 'userId': ?0 }, { 'user._id': ?0 } ] }",count=true)
  Mono<Long> countByUserId(String userId);

  @Query("{ '_id': ?0, '$or': [ { 'userId': ?1 }, { 'user._id': ?1 } ] }")
  Mono<TacoOrder> findByIdAndUserId(String id, String userId);

  Flux<TacoOrder> findAllBy(Pageable pageable);

}
