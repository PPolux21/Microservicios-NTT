package tacos.data;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import reactor.core.publisher.Mono;
import tacos.IdempotencyRecord;

@RepositoryRestResource(exported=false)
public interface IdempotencyRecordRepository
    extends ReactiveMongoRepository<IdempotencyRecord,String> {

  Mono<IdempotencyRecord> findByUserIdAndKey(String userId,String key);
}
