package tacos.web.api.coupon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import tacos.web.api.coupon.CouponProperties.CouponRule;
import tacos.web.api.coupon.CouponProperties.CouponType;

@Service
public class CouponService {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

  private final CouponProperties properties;
  private final Clock clock;

  public CouponService(CouponProperties properties,Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public CouponApplication evaluate(String code,BigDecimal rawSubtotal) {

    BigDecimal subtotal = money(rawSubtotal);
    if (code == null || code.trim().isEmpty()) {
      return CouponApplication.noCoupon(subtotal);
    }

    String normalizedCode = normalize(code);
    Optional<CouponRule> configuredRule = properties.getRules()
        .entrySet()
        .stream()
        .filter(entry -> normalize(entry.getKey()).equals(normalizedCode))
        .map(Map.Entry::getValue)
        .findFirst();

    if (!configuredRule.isPresent()
        || !isApplicable(configuredRule.get(),subtotal)) {
      return CouponApplication.notApplicable(subtotal);
    }

    BigDecimal discount = calculateDiscount(configuredRule.get(),subtotal)
        .min(subtotal)
        .max(BigDecimal.ZERO)
        .setScale(MONEY_SCALE,MONEY_ROUNDING);

    BigDecimal total = subtotal.subtract(discount)
        .max(BigDecimal.ZERO)
        .setScale(MONEY_SCALE,MONEY_ROUNDING);

    return CouponApplication.applied(
        normalizedCode,subtotal,discount,total);
  }

  private boolean isApplicable(CouponRule rule,BigDecimal subtotal) {

    if (rule == null || rule.getType() == null
        || rule.getValue() == null || rule.getValue().signum() < 0) {
      return false;
    }

    LocalDate today = LocalDate.ofInstant(clock.instant(),clock.getZone());
    if (rule.getValidFrom() != null && today.isBefore(rule.getValidFrom())) {
      return false;
    }
    if (rule.getValidUntil() != null && today.isAfter(rule.getValidUntil())) {
      return false;
    }

    BigDecimal minimum = rule.getMinimumPurchase() != null
        ? rule.getMinimumPurchase()
        : BigDecimal.ZERO;
    return subtotal.compareTo(minimum) >= 0;
  }

  private BigDecimal calculateDiscount(CouponRule rule,BigDecimal subtotal) {

    if (rule.getType() == CouponType.FIXED) {
      return money(rule.getValue());
    }

    BigDecimal percentageDiscount = subtotal
        .multiply(rule.getValue())
        .divide(ONE_HUNDRED,MONEY_SCALE,MONEY_ROUNDING);

    if (rule.getMaxDiscount() != null) {
      percentageDiscount = percentageDiscount.min(money(rule.getMaxDiscount()));
    }
    return percentageDiscount;
  }

  private String normalize(String code) {
    return code.trim().toUpperCase(Locale.ROOT);
  }

  private BigDecimal money(BigDecimal value) {
    return (value != null ? value : BigDecimal.ZERO)
        .setScale(MONEY_SCALE,MONEY_ROUNDING);
  }

  @Getter
  @AllArgsConstructor
  public static class CouponApplication {

    private final boolean applicable;
    private final boolean applied;
    private final String normalizedCode;
    private final BigDecimal subtotal;
    private final BigDecimal discount;
    private final BigDecimal total;

    private static CouponApplication noCoupon(BigDecimal subtotal) {
      return new CouponApplication(
          true,false,null,subtotal,BigDecimal.ZERO.setScale(2),subtotal);
    }

    private static CouponApplication notApplicable(BigDecimal subtotal) {
      return new CouponApplication(
          false,false,null,subtotal,BigDecimal.ZERO.setScale(2),subtotal);
    }

    private static CouponApplication applied(
        String code,BigDecimal subtotal,BigDecimal discount,BigDecimal total) {
      return new CouponApplication(
          true,true,code,subtotal,discount,total);
    }
  }
}
