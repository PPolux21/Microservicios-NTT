package tacos.data;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

import tacos.ReorderAttempt;

public interface ReorderAttemptRepository
    extends ReactiveMongoRepository<ReorderAttempt,String> {
}
