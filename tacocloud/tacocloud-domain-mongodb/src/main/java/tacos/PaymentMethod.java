package tacos;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Document
@Data
@NoArgsConstructor(force=true, access=AccessLevel.PRIVATE)
public class PaymentMethod {

  @Id
  private String id;
  
  private final User user;

  @PersistenceConstructor
  public PaymentMethod(User user) {
    this.user = user;
  }

  @JsonIgnore 
  @ToString.Exclude
  private String paymentToken;
  private String brand;

  private String last4;

  private String expiration;

}
