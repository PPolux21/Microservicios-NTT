package tacos.web.api.coupon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix="tacocloud.coupons")
public class CouponProperties {

  private Map<String,CouponRule> rules = new LinkedHashMap<>();

  public enum CouponType {
    PERCENTAGE,
    FIXED
  }

  @Data
  public static class CouponRule {

    private CouponType type;
    private BigDecimal value;
    private LocalDate validFrom;
    private LocalDate validUntil;
    private BigDecimal minimumPurchase = BigDecimal.ZERO;
    private BigDecimal maxDiscount;
  }
}
