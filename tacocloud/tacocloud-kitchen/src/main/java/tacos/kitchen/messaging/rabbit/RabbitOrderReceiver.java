package tacos.kitchen.messaging.rabbit;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import tacos.kitchen.OrderReceiver;
import tacos.messaging.OrderEvent;

@Profile("template")
@ConditionalOnProperty(prefix="tacocloud.messaging",name="transport",
    havingValue="rabbit")
@Component("templateOrderReceiver")
public class RabbitOrderReceiver implements OrderReceiver {

  private RabbitTemplate rabbit;
  private final String destination;

  public RabbitOrderReceiver(RabbitTemplate rabbit,
      @Value("${tacocloud.messaging.rabbit.destination}") String destination) {
    this.rabbit = rabbit;
    this.destination = destination;
  }
  
  public OrderEvent receiveOrder() {
    return (OrderEvent) rabbit.receiveAndConvert(destination);
  }
  
}
