package tacos;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection="opsAnnouncements")
public class OpsAnnouncement {

  public enum Severity {
    INFO,
    WARNING,
    CRITICAL
  }

  @Id
  private String id;

  private String text;

  private Severity severity;

  private Instant createdAt;

  @Indexed(name="ops_announcement_expiry_ttl",expireAfterSeconds=0)
  private Instant expiresAt;

  private String createdBy;

  private boolean active;
}
