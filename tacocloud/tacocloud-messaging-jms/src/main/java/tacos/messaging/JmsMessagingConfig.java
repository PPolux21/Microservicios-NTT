package tacos.messaging;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnProperty(prefix="tacocloud.messaging",name="transport",
    havingValue="jms")
public class JmsMessagingConfig {

  @Bean
  public OrderMessagingService jmsOrderMessagingService(JmsTemplate jms,
      @Value("${tacocloud.messaging.jms.destination}") String destination) {
    return new JmsOrderMessagingService(jms,destination);
  }

  @Bean
  public MappingJackson2MessageConverter jmsOrderMessageConverter(
      ObjectMapper objectMapper) {
    MappingJackson2MessageConverter converter =
        new MappingJackson2MessageConverter();
    converter.setObjectMapper(objectMapper);
    converter.setTypeIdPropertyName("_typeId");

    Map<String,Class<?>> mappings = new HashMap<>();
    mappings.put("order-event-v1",OrderEvent.class);
    converter.setTypeIdMappings(mappings);
    return converter;
  }
}
