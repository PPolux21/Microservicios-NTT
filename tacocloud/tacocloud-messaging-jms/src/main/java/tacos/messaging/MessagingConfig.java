package tacos.messaging;

import java.util.HashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class MessagingConfig {

  @Bean
  public MappingJackson2MessageConverter messageConverter(
      ObjectMapper objectMapper) {
    MappingJackson2MessageConverter messageConverter =
                            new MappingJackson2MessageConverter();
    messageConverter.setObjectMapper(objectMapper);
    messageConverter.setTypeIdPropertyName("_typeId");
    
    Map<String, Class<?>> typeIdMappings = new HashMap<String, Class<?>>();
    typeIdMappings.put("order-event-v1", OrderEvent.class);
    messageConverter.setTypeIdMappings(typeIdMappings);
    
    return messageConverter;
  }

}
