package tacos.web.api.outbox;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="tacocloud.outbox.publisher",name="enabled",
    havingValue="true",matchIfMissing=true)
public class OutboxPublisherScheduler {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(OutboxPublisherScheduler.class);

  private final OutboxPublisher publisher;
  private final AtomicBoolean running = new AtomicBoolean();

  public OutboxPublisherScheduler(OutboxPublisher publisher) {
    this.publisher = publisher;
  }

  @Scheduled(fixedDelayString="${tacocloud.outbox.poll-interval-ms:1000}")
  public void publishAvailableEvents() {
    if (!running.compareAndSet(false,true)) {
      return;
    }
    // This is the single reactive subscription boundary owned by the scheduler.
    publisher.publishBatch()
        .doOnError(error -> LOGGER.error("Outbox publication cycle failed",error))
        .doFinally(signal -> running.set(false))
        .subscribe(null,error -> { });
  }
}
