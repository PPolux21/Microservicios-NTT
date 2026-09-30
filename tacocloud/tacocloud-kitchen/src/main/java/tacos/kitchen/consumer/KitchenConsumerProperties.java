package tacos.kitchen.consumer;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="tacocloud.kitchen.consumer")
public class KitchenConsumerProperties {

  private int maxAttempts = 3;
  private Duration backoff = Duration.ofSeconds(1);
  private int concurrency = 1;

  public int getMaxAttempts() { return maxAttempts; }
  public void setMaxAttempts(int maxAttempts) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
    this.maxAttempts = maxAttempts;
  }
  public Duration getBackoff() { return backoff; }
  public void setBackoff(Duration backoff) {
    if (backoff == null || backoff.isNegative()) {
      throw new IllegalArgumentException("backoff must not be negative");
    }
    this.backoff = backoff;
  }
  public int getConcurrency() { return concurrency; }
  public void setConcurrency(int concurrency) {
    if (concurrency < 1) {
      throw new IllegalArgumentException("concurrency must be at least 1");
    }
    this.concurrency = concurrency;
  }
}
