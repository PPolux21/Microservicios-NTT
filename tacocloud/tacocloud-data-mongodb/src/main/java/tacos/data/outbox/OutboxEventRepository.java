package tacos.data.outbox;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Mono;

public interface OutboxEventRepository
    extends ReactiveCrudRepository<OutboxEvent,String> {

  Mono<OutboxEvent> findByEventId(UUID eventId);
}
