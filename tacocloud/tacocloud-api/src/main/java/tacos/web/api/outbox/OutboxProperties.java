package tacos.web.api.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="tacocloud.outbox")
public class OutboxProperties {

  private int batchSize = 20;
  private int maxAttempts = 5;
  private Duration initialBackoff = Duration.ofSeconds(1);
  private Duration maxBackoff = Duration.ofMinutes(1);
  private Duration claimTimeout = Duration.ofMinutes(2);
  private long healthMaxBacklog = 100;
  private long healthMaxFailed = 0;

  public int getBatchSize() { return batchSize; }
  public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
  public int getMaxAttempts() { return maxAttempts; }
  public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
  public Duration getInitialBackoff() { return initialBackoff; }
  public void setInitialBackoff(Duration initialBackoff) { this.initialBackoff = initialBackoff; }
  public Duration getMaxBackoff() { return maxBackoff; }
  public void setMaxBackoff(Duration maxBackoff) { this.maxBackoff = maxBackoff; }
  public Duration getClaimTimeout() { return claimTimeout; }
  public void setClaimTimeout(Duration claimTimeout) { this.claimTimeout = claimTimeout; }
  public long getHealthMaxBacklog() { return healthMaxBacklog; }
  public void setHealthMaxBacklog(long healthMaxBacklog) {
    this.healthMaxBacklog = healthMaxBacklog;
  }
  public long getHealthMaxFailed() { return healthMaxFailed; }
  public void setHealthMaxFailed(long healthMaxFailed) {
    this.healthMaxFailed = healthMaxFailed;
  }
}
