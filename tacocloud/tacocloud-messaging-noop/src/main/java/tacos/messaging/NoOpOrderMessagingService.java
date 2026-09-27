package tacos.messaging;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import tacos.TacoOrder;

@Service
@Slf4j
public class NoOpOrderMessagingService
       implements OrderMessagingService {
  
  @Override
  public void sendOrder(TacoOrder order) {

    log.info("Order published to kitchen. orderId={}",
      order != null ? order.getId() : null);
  }
  
}
