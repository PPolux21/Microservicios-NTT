package tacos.messaging;

import javax.jms.JMSException;
import javax.jms.Message;

import org.springframework.jms.core.JmsTemplate;

public class JmsOrderMessagingService implements OrderMessagingService {

  private final JmsTemplate jms;
  private final String destination;

  public JmsOrderMessagingService(JmsTemplate jms,String destination) {
    this.jms = jms;
    this.destination = destination;
  }

  @Override
  public void sendOrder(OrderEvent event) {
    jms.convertAndSend(destination,event,
        this::addOrderSource);
  }
  
  private Message addOrderSource(Message message) throws JMSException {
    message.setStringProperty("X_ORDER_SOURCE", "WEB");
    return message;
  }

}
