package tacos;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceConstructor;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor(access=AccessLevel.PRIVATE,force=true)
@Document("tacoRatings")
@CompoundIndex(
    name="rating_user_taco_unique",
    def="{'userId': 1, 'tacoId': 1}",
    unique=true)
public class TacoRating {

  @Id
  private String id;
  private final String userId;
  private final String tacoId;
  private final int score;

  @PersistenceConstructor
  public TacoRating(String userId,String tacoId,int score) {
    this.userId = userId;
    this.tacoId = tacoId;
    this.score = score;
  }
}
