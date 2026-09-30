package tacos.kitchen.messaging.rabbit.listener;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import tacos.kitchen.KitchenUI;
import tacos.messaging.OrderEvent;

@Profile("!template")
@ConditionalOnProperty(prefix="tacocloud.messaging",name="transport",
    havingValue="rabbit")
@Component
public class OrderListener {
  
  private KitchenUI ui;

  @Autowired
  public OrderListener(KitchenUI ui) {
    this.ui = ui;
  }

  @RabbitListener(queues = "${tacocloud.messaging.rabbit.destination}")
  public void receiveOrder(OrderEvent event) {
    ui.displayOrder(event);
  }
  
}
