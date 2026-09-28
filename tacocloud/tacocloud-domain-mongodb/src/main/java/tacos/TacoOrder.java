package tacos;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.validation.constraints.Min;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Document
public class TacoOrder implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  private String id;
  private Date placedAt = new Date();

  private User user;

  private String deliveryName;

  private String deliveryStreet;

  private String deliveryCity;

  private String deliveryState;

  private String deliveryZip;


  private List<Taco> tacos = new ArrayList<>();

  private List<OrderItem> items = new ArrayList<>();

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
    PREPARING
  }
  
  private Status status = Status.CREATED;

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
