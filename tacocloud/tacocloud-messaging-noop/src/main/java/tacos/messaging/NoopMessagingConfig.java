package tacos.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix="tacocloud.messaging",name="transport",
    havingValue="noop",matchIfMissing=true)
public class NoopMessagingConfig {

  @Bean
  public OrderMessagingService noopOrderMessagingService() {
    return new NoOpOrderMessagingService();
  }
}
