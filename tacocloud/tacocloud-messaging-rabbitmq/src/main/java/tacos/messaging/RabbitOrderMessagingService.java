package tacos.messaging;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public class RabbitOrderMessagingService
       implements OrderMessagingService {
  
  private final RabbitTemplate rabbit;
  private final String destination;
  
  public RabbitOrderMessagingService(RabbitTemplate rabbit,
      String destination) {
    this.rabbit = rabbit;
    this.destination = destination;
  }
  
  public void sendOrder(OrderEvent event) {
    rabbit.convertAndSend(destination,event,
        new MessagePostProcessor() {
          @Override
          public Message postProcessMessage(Message message)
              throws AmqpException {
            MessageProperties props = message.getMessageProperties();
            props.setHeader("X_ORDER_SOURCE", "WEB");
            return message;
          } 
        });
  }
  
}
