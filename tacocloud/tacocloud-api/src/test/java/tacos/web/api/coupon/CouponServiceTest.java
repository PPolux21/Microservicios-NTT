package tacos.web.api.coupon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import tacos.web.api.coupon.CouponProperties.CouponRule;
import tacos.web.api.coupon.CouponProperties.CouponType;
import tacos.web.api.coupon.CouponService.CouponApplication;

public class CouponServiceTest {

  @ParameterizedTest
  @CsvSource({"PERCENTAGE,10,10.00", "FIXED,15,15.00"})
  public void shouldCalculateSupportedCouponTypes(
      CouponType type,String value,String expectedDiscount) {
    CouponService service = service(rule(type,value),"2026-06-15");

    CouponApplication result = service.evaluate("PROMO",new BigDecimal("100.00"));

    assertTrue(result.isApplicable());
    assertEquals(new BigDecimal(expectedDiscount),result.getDiscount());
    assertEquals(new BigDecimal("100.00").subtract(result.getDiscount()),result.getTotal());
  }

  @ParameterizedTest
  @CsvSource({
      "2026-06-09,false",
      "2026-06-10,true",
      "2026-06-20,true",
      "2026-06-21,false"
  })
  public void shouldUseInclusiveValidityDates(String date,boolean expected) {
    CouponRule rule = rule(CouponType.PERCENTAGE,"10");
    rule.setValidFrom(LocalDate.of(2026,6,10));
    rule.setValidUntil(LocalDate.of(2026,6,20));

    assertEquals(expected,
        service(rule,date).evaluate("PROMO",new BigDecimal("100.00")).isApplicable());
  }

  @ParameterizedTest
  @CsvSource({"199.99,false", "200.00,true", "200.01,true"})
  public void shouldEnforceMinimumPurchase(String subtotal,boolean expected) {
    CouponRule rule = rule(CouponType.PERCENTAGE,"10");
    rule.setMinimumPurchase(new BigDecimal("200.00"));

    assertEquals(expected,
        service(rule,"2026-06-15").evaluate("PROMO",new BigDecimal(subtotal))
            .isApplicable());
  }

  @Test
  public void shouldCapPercentageDiscount() {
    CouponRule rule = rule(CouponType.PERCENTAGE,"50");
    rule.setMaxDiscount(new BigDecimal("200.00"));

    CouponApplication result = service(rule,"2026-06-15")
        .evaluate("PROMO",new BigDecimal("1000.00"));

    assertEquals(new BigDecimal("200.00"),result.getDiscount());
    assertEquals(new BigDecimal("800.00"),result.getTotal());
  }

  @Test
  public void shouldNeverProduceNegativeTotal() {
    CouponApplication result = service(rule(CouponType.FIXED,"100"),"2026-06-15")
        .evaluate("PROMO",new BigDecimal("50.00"));

    assertEquals(new BigDecimal("50.00"),result.getDiscount());
    assertEquals(new BigDecimal("0.00"),result.getTotal());
  }

  @ParameterizedTest
  @ValueSource(strings={"promo10","PROMO10"," Promo10 "})
  public void shouldNormalizeCodesCaseInsensitively(String code) {
    CouponProperties properties = new CouponProperties();
    properties.getRules().put("promo10",rule(CouponType.FIXED,"5"));
    CouponService service = new CouponService(properties,fixedClock("2026-06-15"));

    CouponApplication result = service.evaluate(code,new BigDecimal("20.00"));

    assertTrue(result.isApplied());
    assertEquals("PROMO10",result.getNormalizedCode());
  }

  @Test
  public void shouldReturnGenericNotApplicableResultWithoutRuleDetails() {
    CouponService service = service(rule(CouponType.FIXED,"5"),"2026-06-15");

    CouponApplication result = service.evaluate("UNKNOWN",new BigDecimal("20.00"));

    assertFalse(result.isApplicable());
    assertFalse(result.isApplied());
    assertEquals(null,result.getNormalizedCode());
    assertEquals(new BigDecimal("0.00"),result.getDiscount());
  }

  private CouponService service(CouponRule rule,String date) {
    CouponProperties properties = new CouponProperties();
    properties.getRules().put("PROMO",rule);
    return new CouponService(properties,fixedClock(date));
  }

  private CouponRule rule(CouponType type,String value) {
    CouponRule rule = new CouponRule();
    rule.setType(type);
    rule.setValue(new BigDecimal(value));
    return rule;
  }

  private Clock fixedClock(String date) {
    return Clock.fixed(Instant.parse(date + "T12:00:00Z"),ZoneOffset.UTC);
  }
}
