package tacos.kitchen.consumer;

import java.time.Clock;

import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;
import tacos.data.consumer.ProcessedEvent;
import tacos.data.consumer.ProcessedEventRepository;
import tacos.messaging.OrderEvent;

@Service
public class OrderEventConsumerService {

  private final ProcessedEventRepository processedEvents;
  private final OrderEventBusinessHandler businessHandler;
  private final TransactionalOperator transactions;
  private final OrderEventConsumerMetrics metrics;
  private final Clock clock;

  public OrderEventConsumerService(ProcessedEventRepository processedEvents,
      OrderEventBusinessHandler businessHandler,
      TransactionalOperator transactions,OrderEventConsumerMetrics metrics,
      Clock clock) {
    this.processedEvents = processedEvents;
    this.businessHandler = businessHandler;
    this.transactions = transactions;
    this.metrics = metrics;
    this.clock = clock;
  }

  public Mono<OrderEventProcessingResult> process(OrderEvent event) {
    return Mono.defer(() -> {
      validate(event);
      Mono<OrderEventProcessingResult> operation = processedEvents
          .findByEventId(event.getEventId())
          .map(ignored -> OrderEventProcessingResult.DUPLICATE)
          .switchIfEmpty(businessHandler.apply(event)
              .then(processedEvents.save(ProcessedEvent.completed(
                  event,clock.instant())))
              .thenReturn(OrderEventProcessingResult.PROCESSED));

      return transactions.transactional(operation)
          .onErrorResume(this::isDuplicateKey,error -> processedEvents
              .findByEventId(event.getEventId())
              .map(ignored -> OrderEventProcessingResult.DUPLICATE));
    }).doOnNext(result -> {
      if (result == OrderEventProcessingResult.DUPLICATE) {
        metrics.duplicate(event);
      } else {
        metrics.processed(event);
      }
    });
  }

  private void validate(OrderEvent event) {
    if (event == null) {
      throw new PermanentOrderEventException(
          "INVALID_EVENT","Order event is required.");
    }
    if (event.getVersion() != OrderEvent.CURRENT_VERSION) {
      throw new PermanentOrderEventException(
          "UNSUPPORTED_EVENT_VERSION",
          "Unsupported order event version: " + event.getVersion());
    }
    if (event.getEventType() == null) {
      throw new PermanentOrderEventException(
          "UNSUPPORTED_EVENT_TYPE","Order event type is required.");
    }
    if (event.getPayload() == null
        || event.getPayload().getOrderId() == null) {
      throw new PermanentOrderEventException(
          "INVALID_EVENT_PAYLOAD","Order event payload is invalid.");
    }
  }

  private boolean isDuplicateKey(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof DuplicateKeyException) {
        return true;
      }
      if (current instanceof MongoWriteException
          && ((MongoWriteException) current).getError().getCode() == 11000) {
        return true;
      }
      if (current instanceof MongoException
          && ((MongoException) current).getCode() == 11000) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
