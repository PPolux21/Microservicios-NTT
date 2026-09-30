package tacos.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
public class NoOpOrderMessagingServiceTest {

  @Test
  public void shouldLogOnlyOrderIdentifier() {

    Logger logger = (Logger) LoggerFactory.getLogger(
        NoOpOrderMessagingService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try {
      OrderEvent event = new OrderEvent(
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          OrderEventType.ORDER_CREATED,1,Instant.parse("2026-09-29T18:00:00Z"),
          "corr-1",new OrderEventPayload(
              "ORDER-SYNTHETIC-1","CREATED",null,
              Instant.parse("2026-09-29T17:59:00Z"),
              Collections.emptyList(),null,null,null));

      new NoOpOrderMessagingService().sendOrder(event);

      assertEquals(1,appender.list.size());
      assertEquals(
          "Order event published to kitchen. eventId="
              + "00000000-0000-0000-0000-000000000001 "
              + "orderId=ORDER-SYNTHETIC-1 type=ORDER_CREATED",
          appender.list.get(0).getFormattedMessage());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
