package tacos.data.consumer;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventType;

@Document("processed_order_events")
public class ProcessedEvent {

  @Id
  private String id;

  @Indexed(name="processed_event_id_unique",unique=true)
  private UUID eventId;

  private OrderEventType eventType;
  private Instant processedAt;
  private String result;
  private String correlationId;

  public ProcessedEvent() {
  }

  private ProcessedEvent(OrderEvent event,Instant processedAt,String result) {
    this.eventId = event.getEventId();
    this.eventType = event.getEventType();
    this.processedAt = processedAt;
    this.result = result;
    this.correlationId = event.getCorrelationId();
  }

  public static ProcessedEvent completed(OrderEvent event,Instant processedAt) {
    return new ProcessedEvent(event,processedAt,"PROCESSED");
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public UUID getEventId() { return eventId; }
  public void setEventId(UUID eventId) { this.eventId = eventId; }
  public OrderEventType getEventType() { return eventType; }
  public void setEventType(OrderEventType eventType) { this.eventType = eventType; }
  public Instant getProcessedAt() { return processedAt; }
  public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
  public String getResult() { return result; }
  public void setResult(String result) { this.result = result; }
  public String getCorrelationId() { return correlationId; }
  public void setCorrelationId(String correlationId) {
    this.correlationId = correlationId;
  }
}
