package tacos.actuator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

import java.util.Collection;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.boot.actuate.health.Status;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.data.outbox.OutboxEventRepository;
import tacos.data.outbox.OutboxStatus;
import tacos.web.api.outbox.OutboxProperties;

public class OutboxHealthIndicatorTest {

  @Test
  public void shouldExplainDegradedBacklogWithoutSecrets() {
    OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
    OutboxProperties properties = new OutboxProperties();
    properties.setHealthMaxBacklog(2);
    properties.setHealthMaxFailed(0);
    when(repository.countByStatusIn(ArgumentMatchers
        .<Collection<OutboxStatus>>any())).thenReturn(Mono.just(3L));
    when(repository.countByStatus(OutboxStatus.FAILED)).thenReturn(Mono.just(0L));

    OutboxHealthIndicator indicator = new OutboxHealthIndicator(
        repository,properties);

    StepVerifier.create(indicator.health())
        .assertNext(health -> {
          assertEquals(Status.DOWN,health.getStatus());
          assertEquals("outbox",health.getDetails().get("component"));
          assertEquals("backlog-threshold-exceeded",
              health.getDetails().get("reason"));
          assertEquals(3L,health.getDetails().get("pending"));
          String details = health.getDetails().toString().toLowerCase();
          assertFalse(details.contains("password"));
          assertFalse(details.contains("mongodb://"));
          assertFalse(details.contains("token"));
        })
        .verifyComplete();
  }
}
