package tacos.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnProperty(prefix="tacocloud.messaging",name="transport",
    havingValue="rabbit")
public class RabbitMessagingConfig {

  @Bean
  public OrderMessagingService rabbitOrderMessagingService(
      RabbitTemplate rabbit,
      @Value("${tacocloud.messaging.rabbit.destination}") String destination) {
    return new RabbitOrderMessagingService(rabbit,destination);
  }

  @Bean
  public Jackson2JsonMessageConverter rabbitOrderMessageConverter(
      ObjectMapper objectMapper) {
    return new Jackson2JsonMessageConverter(objectMapper);
  }
}
