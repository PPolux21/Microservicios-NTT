package tacos.kitchen.messaging.rabbit;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.fasterxml.jackson.databind.ObjectMapper;

@Profile({"rabbitmq-template", "rabbitmq-listener"})
@Configuration
public class MessagingConfig {

  @Bean
  public Jackson2JsonMessageConverter messageConverter(
      ObjectMapper objectMapper) {
    return new Jackson2JsonMessageConverter(objectMapper);
  }

}
