package tacos.data.consumer;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Mono;

public interface ProcessedEventRepository
    extends ReactiveCrudRepository<ProcessedEvent,String> {

  Mono<ProcessedEvent> findByEventId(UUID eventId);
}
