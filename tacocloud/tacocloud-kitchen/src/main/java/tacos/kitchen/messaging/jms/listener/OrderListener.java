package tacos.kitchen.messaging.jms.listener;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import tacos.kitchen.KitchenUI;
import tacos.messaging.OrderEvent;

@Profile("!template")
@ConditionalOnProperty(prefix="tacocloud.messaging",name="transport",
    havingValue="jms")
@Component
public class OrderListener {
  
  private KitchenUI ui;

  @Autowired
  public OrderListener(KitchenUI ui) {
    this.ui = ui;
  }

  @JmsListener(destination = "${tacocloud.messaging.jms.destination}")
  public void receiveOrder(OrderEvent event) {
    ui.displayOrder(event);
  }
  
}
