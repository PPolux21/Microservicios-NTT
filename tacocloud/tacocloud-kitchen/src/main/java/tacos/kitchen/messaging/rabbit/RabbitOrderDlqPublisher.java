package tacos.kitchen.messaging.rabbit;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tacos.kitchen.consumer.PermanentOrderEventException;
import tacos.messaging.OrderEvent;

@Component
@ConditionalOnProperty(prefix="tacocloud.messaging",name="transport",
    havingValue="rabbit")
public class RabbitOrderDlqPublisher {

  public static final String CAUSE_HEADER = "x-tacocloud-error-code";
  public static final String CORRELATION_HEADER =
      "x-tacocloud-correlation-id";
  public static final String EVENT_ID_HEADER = "x-tacocloud-event-id";
  public static final String ORIGINAL_DESTINATION_HEADER =
      "x-tacocloud-original-destination";

  private final RabbitTemplate rabbit;
  private final RabbitConsumerProperties properties;

  public RabbitOrderDlqPublisher(RabbitTemplate rabbit,
      RabbitConsumerProperties properties) {
    this.rabbit = rabbit;
    this.properties = properties;
  }

  public Mono<Void> publish(OrderEvent event,Throwable failure) {
    Throwable cause = unwrap(failure);
    MessagePostProcessor safeHeaders = message -> {
      message.getMessageProperties().setHeader(
          CAUSE_HEADER,errorCode(cause));
      message.getMessageProperties().setHeader(
          CORRELATION_HEADER,event.getCorrelationId());
      message.getMessageProperties().setHeader(
          EVENT_ID_HEADER,event.getEventId().toString());
      message.getMessageProperties().setHeader(
          ORIGINAL_DESTINATION_HEADER,properties.getDestination());
      return message;
    };

    return Mono.fromRunnable(() -> rabbit.convertAndSend(
            properties.getDeadLetterExchange(),
            properties.getDeadLetterRoutingKey(),event,safeHeaders))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  private Throwable unwrap(Throwable failure) {
    if (Exceptions.isRetryExhausted(failure) && failure.getCause() != null) {
      return failure.getCause();
    }
    return Exceptions.unwrap(failure);
  }

  private String errorCode(Throwable failure) {
    if (failure instanceof PermanentOrderEventException) {
      return ((PermanentOrderEventException) failure).getCode();
    }
    return "TRANSIENT_RETRIES_EXHAUSTED";
  }
}
