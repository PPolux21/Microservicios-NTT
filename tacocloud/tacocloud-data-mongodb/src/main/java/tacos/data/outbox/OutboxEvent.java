package tacos.data.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventType;

@Document(collection="outbox_events")
@CompoundIndexes({
    @CompoundIndex(name="outbox_publishable_idx",
        def="{'status': 1, 'nextAttemptAt': 1, 'createdAt': 1}"),
    @CompoundIndex(name="outbox_stale_claim_idx",
        def="{'status': 1, 'claimedAt': 1}")
})
public class OutboxEvent {

  @Id
  private String id;

  @Indexed(unique=true)
  private UUID eventId;

  private OrderEventType eventType;
  private int version;
  private OrderEvent event;
  private OutboxStatus status;
  private int attempts;
  private Instant createdAt;
  private Instant updatedAt;
  private Instant nextAttemptAt;
  private Instant claimedAt;
  private String claimedBy;
  private Instant publishedAt;
  private String lastError;

  public OutboxEvent() {
  }

  private OutboxEvent(OrderEvent event,Instant now) {
    this.event = Objects.requireNonNull(event,"event is required");
    this.eventId = event.getEventId();
    this.eventType = event.getEventType();
    this.version = event.getVersion();
    this.status = OutboxStatus.NEW;
    this.attempts = 0;
    this.createdAt = Objects.requireNonNull(now,"now is required");
    this.updatedAt = now;
    this.nextAttemptAt = now;
  }

  public static OutboxEvent pending(OrderEvent event,Instant now) {
    return new OutboxEvent(event,now);
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public UUID getEventId() { return eventId; }
  public void setEventId(UUID eventId) { this.eventId = eventId; }
  public OrderEventType getEventType() { return eventType; }
  public void setEventType(OrderEventType eventType) { this.eventType = eventType; }
  public int getVersion() { return version; }
  public void setVersion(int version) { this.version = version; }
  public OrderEvent getEvent() { return event; }
  public void setEvent(OrderEvent event) { this.event = event; }
  public OutboxStatus getStatus() { return status; }
  public void setStatus(OutboxStatus status) { this.status = status; }
  public int getAttempts() { return attempts; }
  public void setAttempts(int attempts) { this.attempts = attempts; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
  public Instant getNextAttemptAt() { return nextAttemptAt; }
  public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
  public Instant getClaimedAt() { return claimedAt; }
  public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
  public String getClaimedBy() { return claimedBy; }
  public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
  public Instant getPublishedAt() { return publishedAt; }
  public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
  public String getLastError() { return lastError; }
  public void setLastError(String lastError) { this.lastError = lastError; }
}
