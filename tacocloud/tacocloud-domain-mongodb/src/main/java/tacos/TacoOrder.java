package tacos;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.validation.constraints.Min;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Document
@CompoundIndexes({
    @CompoundIndex(name="order_user_placed_id_idx",
        def="{'userId':1,'placedAt':-1,'_id':1}"),
    @CompoundIndex(name="order_kitchen_queue_idx",
        def="{'status':1,'placedAt':1,'_id':1}")
})
public class TacoOrder implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  private String id;

  @Version
  private Long version;

  private Date placedAt = new Date();

  private String userId;

  @Transient
  private User user;

  public void setUser(User user) {
    this.user = user;
    this.userId = user != null ? user.getId() : null;
  }

  private String deliveryName;

  private String deliveryStreet;

  private String deliveryCity;

  private String deliveryState;

  private String deliveryZip;


  private List<Taco> tacos = new ArrayList<>();

  private List<OrderItem> items = new ArrayList<>();

  private BigDecimal subtotal = BigDecimal.ZERO.setScale(2);

  private String appliedCouponCode;

  private BigDecimal discountAmount = BigDecimal.ZERO.setScale(2);

  private BigDecimal total = BigDecimal.ZERO.setScale(2);

  private String currency = "MXN";

  public void addTaco(Taco design) {
    this.tacos.add(design);
  }

  public void addItem(OrderItem item) {
    this.items.add(item);
    if (item != null && item.getTaco() != null) {
      this.tacos.add(item.getTaco());
    }
  }

  public enum Status {
    CREATED,
    ACCEPTED,
    PREPARING,
    READY,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED
  }
  
  private Status status = Status.CREATED;

  private String stationId;

  private String cookId;

  @Indexed(name="active_kitchen_station_unique",unique=true,sparse=true)
  private String activeKitchenStationKey;

  private List<OrderStatusHistoryEntry> statusHistory = new ArrayList<>();

  public void addStatusHistory(OrderStatusHistoryEntry entry) {
    if (statusHistory == null) {
      statusHistory = new ArrayList<>();
    }
    statusHistory.add(entry);
  }

  public enum ChangeOrigin {
    USER_API,
    KITCHEN_API,
    ADMIN_API,
    SYSTEM
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class OrderStatusHistoryEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private Status fromStatus;
    private Status toStatus;
    private Date changedAt;
    private String changedBy;
    private ChangeOrigin origin;
    private String reason;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class OrderItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private Taco taco;

    @Min(1)
    private int quantity;

    private BigDecimal unitPriceAtPurchase;

    private BigDecimal subtotal;
  }
}
