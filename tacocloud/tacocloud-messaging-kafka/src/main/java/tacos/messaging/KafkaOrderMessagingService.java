package tacos.messaging;

import org.springframework.kafka.core.KafkaTemplate;

public class KafkaOrderMessagingService
                                  implements OrderMessagingService {
  
  private final KafkaTemplate<String, OrderEvent> kafkaTemplate;
  private final String topic;
  
  public KafkaOrderMessagingService(
          KafkaTemplate<String, OrderEvent> kafkaTemplate,String topic) {
    this.kafkaTemplate = kafkaTemplate;
    this.topic = topic;
  }
  
  @Override
  public void sendOrder(OrderEvent event) {
    kafkaTemplate.send(topic,event);
  }
  
}
