package tacos;

import java.util.Date;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceConstructor;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor(access=AccessLevel.PRIVATE,force=true)
@Document("favorites")
@CompoundIndex(
    name="favorite_user_taco_unique",
    def="{'userId': 1, 'tacoId': 1}",
    unique=true)
public class Favorite {

  @Id
  private String id;
  private final String userId;
  private final String tacoId;
  private final Date createdAt;

  public Favorite(String userId,String tacoId) {
    this(userId,tacoId,new Date());
  }

  @PersistenceConstructor
  public Favorite(String userId,String tacoId,Date createdAt) {
    this.userId = userId;
    this.tacoId = tacoId;
    this.createdAt = createdAt;
  }
}
