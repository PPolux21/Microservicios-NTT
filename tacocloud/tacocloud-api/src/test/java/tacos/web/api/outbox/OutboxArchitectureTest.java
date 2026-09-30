package tacos.web.api.outbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import tacos.messaging.OrderMessagingService;
import tacos.web.api.OrderApiController;
import tacos.web.api.OrderService;
import tacos.web.api.OrderWorkflowService;

public class OutboxArchitectureTest {

  @Test
  public void onlyPublisherShouldDependOnMessagingPort() {
    assertFalse(hasMessagingPort(OrderService.class));
    assertFalse(hasMessagingPort(OrderWorkflowService.class));
    assertFalse(hasMessagingPort(OrderApiController.class));
    assertTrue(hasMessagingPort(OutboxPublisher.class));
  }

  @Test
  public void publisherShouldNotDependOnConcreteBrokerTypes() {
    assertFalse(Arrays.stream(OutboxPublisher.class.getDeclaredFields())
        .map(Field::getType)
        .map(Class::getName)
        .anyMatch(name -> name.startsWith("javax.jms")
            || name.startsWith("org.springframework.amqp")
            || name.startsWith("org.springframework.kafka")));
  }

  private boolean hasMessagingPort(Class<?> type) {
    return Arrays.stream(type.getDeclaredFields())
        .anyMatch(field -> field.getType() == OrderMessagingService.class);
  }
}
