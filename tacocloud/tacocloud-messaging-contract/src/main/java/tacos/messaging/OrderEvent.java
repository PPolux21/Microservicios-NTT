package tacos.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown=true)
public final class OrderEvent {

  public static final int CURRENT_VERSION = 1;

  private final UUID eventId;
  private final OrderEventType eventType;
  private final int version;
  private final Instant occurredAt;
  private final String correlationId;
  private final OrderEventPayload payload;

  @JsonCreator
  public OrderEvent(
      @JsonProperty(value="eventId",required=true) UUID eventId,
      @JsonProperty(value="eventType",required=true) OrderEventType eventType,
      @JsonProperty(value="version",required=true) int version,
      @JsonProperty(value="occurredAt",required=true) Instant occurredAt,
      @JsonProperty(value="correlationId",required=true) String correlationId,
      @JsonProperty(value="payload",required=true) OrderEventPayload payload) {
    this.eventId = Objects.requireNonNull(eventId,"eventId is required");
    this.eventType = Objects.requireNonNull(eventType,"eventType is required");
    if (version < 1) {
      throw new IllegalArgumentException("version must be positive");
    }
    this.version = version;
    this.occurredAt = Objects.requireNonNull(occurredAt,"occurredAt is required");
    this.correlationId = requireText(correlationId,"correlationId");
    this.payload = Objects.requireNonNull(payload,"payload is required");
  }

  public static OrderEvent create(OrderEventType eventType,
      String correlationId,OrderEventPayload payload) {
    return new OrderEvent(UUID.randomUUID(),eventType,CURRENT_VERSION,
        Instant.now(),correlationId,payload);
  }

  private static String requireText(String value,String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  public UUID getEventId() {
    return eventId;
  }

  public OrderEventType getEventType() {
    return eventType;
  }

  public int getVersion() {
    return version;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public OrderEventPayload getPayload() {
    return payload;
  }
}
