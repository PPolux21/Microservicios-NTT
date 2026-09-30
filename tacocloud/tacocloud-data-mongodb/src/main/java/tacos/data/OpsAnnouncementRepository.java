package tacos.data;

import java.time.Instant;

import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.OpsAnnouncement;

@RepositoryRestResource(exported=false)
public interface OpsAnnouncementRepository
    extends ReactiveCrudRepository<OpsAnnouncement,String> {

  Flux<OpsAnnouncement> findByActiveTrueAndExpiresAtAfterOrderByCreatedAtDesc(
      Instant now);

  Mono<Long> countByActiveTrueAndExpiresAtAfter(Instant now);
}
