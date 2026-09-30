package tacos.messaging;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
@Service
@Slf4j
public class NoOpOrderMessagingService
       implements OrderMessagingService {
  
  @Override
  public void sendOrder(OrderEvent event) {

    log.info("Order event published to kitchen. eventId={} orderId={} type={}",
        event != null ? event.getEventId() : null,
        event != null && event.getPayload() != null
            ? event.getPayload().getOrderId() : null,
        event != null ? event.getEventType() : null);
  }
  
}
