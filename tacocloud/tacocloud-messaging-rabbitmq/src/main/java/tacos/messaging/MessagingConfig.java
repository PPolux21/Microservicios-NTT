package tacos.messaging;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class MessagingConfig {

  @Bean
  public Jackson2JsonMessageConverter messageConverter(
      ObjectMapper objectMapper) {
    return new Jackson2JsonMessageConverter(objectMapper);
  }

}
