package tacos.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tacos.TacoOrder;

public class NoOpOrderMessagingServiceTest {

  @Test
  public void shouldLogOnlyOrderIdentifier() {

    Logger logger = (Logger) LoggerFactory.getLogger(
        NoOpOrderMessagingService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try {
      TacoOrder order = mock(TacoOrder.class);
      when(order.getId()).thenReturn("ORDER-SYNTHETIC-1");

      new NoOpOrderMessagingService().sendOrder(order);

      assertEquals(1,appender.list.size());
      assertEquals(
          "Order published to kitchen. orderId=ORDER-SYNTHETIC-1",
          appender.list.get(0).getFormattedMessage());
      verify(order).getId();
      verifyNoMoreInteractions(order);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
