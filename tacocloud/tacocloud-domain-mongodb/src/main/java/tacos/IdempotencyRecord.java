package tacos;

import java.io.Serializable;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection="idempotencyRecords")
@CompoundIndex(name="idempotency_user_key_unique",
    def="{'userId':1,'key':1}",unique=true)
public class IdempotencyRecord implements Serializable {

  private static final long serialVersionUID = 1L;

  @Id
  private String id;
  private String userId;
  private String key;
  private String requestHash;
  private String orderId;
  private Status status;
  private Instant createdAt;
  private Instant updatedAt;

  @Indexed(name="idempotency_expiry_ttl",expireAfterSeconds=0)
  private Instant expiresAt;

  public enum Status {
    IN_PROGRESS,
    COMPLETED,
    FAILED
  }
}
