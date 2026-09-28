package tacos;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection="inventoryReservations")
public class InventoryReservation implements Serializable {

  private static final long serialVersionUID = 1L;

  @Id
  private String id;
  private String orderId;
  private Status status;
  private List<ReservationItem> items = new ArrayList<>();

  public enum Status {
    PENDING,
    RESERVED,
    RELEASED
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ReservationItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private String ingredientId;
    private int quantity;
  }
}
