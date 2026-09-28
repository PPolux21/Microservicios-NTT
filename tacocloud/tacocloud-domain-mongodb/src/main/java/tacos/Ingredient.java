package tacos;

import java.math.BigDecimal;
import java.math.RoundingMode;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor(access=AccessLevel.PRIVATE, force=true)
@Document
public class Ingredient {

  @Id
  private String id;
  private String name;
  private Type type;

  @NotNull
  @DecimalMin("0.00")
  private BigDecimal unitPrice = BigDecimal.ZERO.setScale(2);

  private boolean available;

  @Min(0)
  private int stockOnHand;

  @Min(0)
  private int reorderLevel;

  @Version
  private Long version;

  public Ingredient(String id,String name,Type type) {
    this(id,name,type,BigDecimal.ZERO,false,0,0);
  }

  public Ingredient(String id,String name,Type type,BigDecimal unitPrice,
      boolean available,int stockOnHand,int reorderLevel) {

    this.id = id;
    this.name = name;
    this.type = type;
    setUnitPrice(unitPrice);
    this.available = available;
    this.stockOnHand = stockOnHand;
    this.reorderLevel = reorderLevel;
  }

  public void setUnitPrice(BigDecimal unitPrice) {
    this.unitPrice = unitPrice != null
        ? unitPrice.setScale(2,RoundingMode.HALF_UP)
        : null;
  }

  @AssertTrue(message="available ingredients must have stock")
  @JsonIgnore
  public boolean isCatalogStateValid() {
    return !available || stockOnHand > 0;
  }

  public enum Type {
    WRAP, PROTEIN, VEGGIES, CHEESE, SAUCE
  }

}
