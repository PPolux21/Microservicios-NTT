package tacos.kitchen.messaging.rabbit.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

import com.mongodb.MongoException;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tacos.kitchen.consumer.KitchenConsumerProperties;
import tacos.kitchen.consumer.OrderEventConsumerMetrics;
import tacos.kitchen.consumer.OrderEventConsumerService;
import tacos.kitchen.consumer.TransientOrderEventException;
import tacos.kitchen.messaging.rabbit.RabbitOrderDlqPublisher;
import tacos.messaging.OrderEvent;

@Profile("!template")
@ConditionalOnProperty(prefix="tacocloud.messaging",name="transport",
    havingValue="rabbit")
@Component
public class OrderListener {

  private static final String CORRELATION_ID = "correlationId";
  private static final Logger LOGGER = LoggerFactory.getLogger(OrderListener.class);

  private final OrderEventConsumerService consumer;
  private final KitchenConsumerProperties properties;
  private final RabbitOrderDlqPublisher dlq;
  private final OrderEventConsumerMetrics metrics;

  public OrderListener(OrderEventConsumerService consumer,
      KitchenConsumerProperties properties,RabbitOrderDlqPublisher dlq,
      OrderEventConsumerMetrics metrics) {
    this.consumer = consumer;
    this.properties = properties;
    this.dlq = dlq;
    this.metrics = metrics;
  }

  @RabbitListener(
      queues="${tacocloud.messaging.rabbit.destination}",
      containerFactory="rabbitOrderListenerContainerFactory")
  public Mono<Void> receiveOrder(OrderEvent event) {
    withCorrelation(event,() -> LOGGER.info(
        "Order event received eventId={} eventType={}",
        event.getEventId(),event.getEventType()));
    Mono<?> processing = Mono.defer(() -> consumer.process(event));
    if (properties.getMaxAttempts() > 1) {
      processing = processing.retryWhen(Retry.fixedDelay(
              properties.getMaxAttempts() - 1,properties.getBackoff())
          .filter(this::isTransient)
          .doBeforeRetry(signal -> metrics.retry(event)));
    }
    return processing
        .doOnSuccess(ignored -> withCorrelation(event,() -> LOGGER.info(
            "Order event processed eventId={} eventType={}",
            event.getEventId(),event.getEventType())))
        .then()
        .onErrorResume(error -> {
          withCorrelation(event,() -> LOGGER.warn(
              "Order event sent to dead letter eventId={} eventType={} errorType={}",
              event.getEventId(),event.getEventType(),
              error.getClass().getSimpleName()));
          return dlq.publish(event,error)
              .doOnSuccess(ignored -> metrics.dlq(event));
        });
  }

  private void withCorrelation(OrderEvent event,Runnable action) {
    String previous = MDC.get(CORRELATION_ID);
    try {
      MDC.put(CORRELATION_ID,event.getCorrelationId());
      action.run();
    } finally {
      if (previous == null) {
        MDC.remove(CORRELATION_ID);
      } else {
        MDC.put(CORRELATION_ID,previous);
      }
    }
  }

  private boolean isTransient(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof TransientOrderEventException
          || current instanceof TransientDataAccessException) {
        return true;
      }
      if (current instanceof MongoException
          && (((MongoException) current).hasErrorLabel(
              MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
              || ((MongoException) current).getCode() == 112)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
