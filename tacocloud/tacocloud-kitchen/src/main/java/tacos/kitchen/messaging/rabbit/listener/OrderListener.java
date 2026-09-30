package tacos.kitchen.messaging.rabbit.listener;

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
    Mono<?> processing = Mono.defer(() -> consumer.process(event));
    if (properties.getMaxAttempts() > 1) {
      processing = processing.retryWhen(Retry.fixedDelay(
              properties.getMaxAttempts() - 1,properties.getBackoff())
          .filter(this::isTransient)
          .doBeforeRetry(signal -> metrics.retry(event)));
    }
    return processing.then()
        .onErrorResume(error -> dlq.publish(event,error)
            .doOnSuccess(ignored -> metrics.dlq(event)));
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
