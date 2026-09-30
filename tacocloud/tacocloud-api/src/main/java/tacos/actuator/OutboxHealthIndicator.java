package tacos.actuator;

import java.util.Arrays;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import tacos.data.outbox.OutboxEventRepository;
import tacos.data.outbox.OutboxStatus;
import tacos.web.api.outbox.OutboxProperties;

@Component
public class OutboxHealthIndicator implements ReactiveHealthIndicator {

  private final OutboxEventRepository outbox;
  private final OutboxProperties properties;

  public OutboxHealthIndicator(OutboxEventRepository outbox,
      OutboxProperties properties) {
    this.outbox = outbox;
    this.properties = properties;
  }

  @Override
  public Mono<Health> health() {
    Mono<Long> pending = outbox.countByStatusIn(Arrays.asList(
        OutboxStatus.NEW,OutboxStatus.PUBLISHING));
    Mono<Long> failed = outbox.countByStatus(OutboxStatus.FAILED);

    return Mono.zip(pending,failed)
        .map(counts -> health(counts.getT1(),counts.getT2()))
        .onErrorResume(error -> Mono.just(Health.down()
            .withDetail("component","outbox")
            .withDetail("reason","query-failed")
            .withDetail("errorType",error.getClass().getSimpleName())
            .build()));
  }

  private Health health(long pending,long failed) {
    boolean backlogExceeded = pending > properties.getHealthMaxBacklog();
    boolean failedExceeded = failed > properties.getHealthMaxFailed();
    Health.Builder builder = backlogExceeded || failedExceeded
        ? Health.down() : Health.up();
    String reason = backlogExceeded
        ? "backlog-threshold-exceeded"
        : failedExceeded ? "failed-events-threshold-exceeded" : "within-thresholds";
    return builder
        .withDetail("component","outbox")
        .withDetail("reason",reason)
        .withDetail("pending",pending)
        .withDetail("failed",failed)
        .build();
  }
}
