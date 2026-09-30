package tacos;

import java.io.Serializable;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection="reorderAttempts")
public class ReorderAttempt implements Serializable {

  private static final long serialVersionUID = 1L;

  @Id
  private String id;
  private String userId;
  private String originalOrderId;
  private String newOrderId;
  private Status status;

  public enum Status {
    PENDING,
    CREATED
  }
}
