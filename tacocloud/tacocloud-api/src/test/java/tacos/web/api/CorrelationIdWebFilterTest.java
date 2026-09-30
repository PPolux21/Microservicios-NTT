package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import reactor.core.publisher.Mono;

public class CorrelationIdWebFilterTest {

  private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();

  @Test
  public void shouldGenerateUuidAndExposeItInResponseAndReactorContext() {
    MockServerWebExchange exchange = exchange("/generated",null);
    AtomicReference<String> contextValue = new AtomicReference<>();

    filter.filter(exchange,ignored -> CorrelationIdWebFilter
        .currentCorrelationId().doOnNext(contextValue::set).then()).block();

    String responseValue = exchange.getResponse().getHeaders()
        .getFirst(CorrelationIdWebFilter.HEADER_NAME);
    assertEquals(responseValue,contextValue.get());
    assertEquals(responseValue,UUID.fromString(responseValue).toString());
  }

  @Test
  public void shouldPreserveValidHeaderExactly() {
    String expected = "test-correlation-123";
    MockServerWebExchange exchange = exchange("/preserved",expected);
    AtomicReference<String> contextValue = new AtomicReference<>();

    filter.filter(exchange,ignored -> CorrelationIdWebFilter
        .currentCorrelationId().doOnNext(contextValue::set).then()).block();

    assertEquals(expected,exchange.getResponse().getHeaders()
        .getFirst(CorrelationIdWebFilter.HEADER_NAME));
    assertEquals(expected,contextValue.get());
  }

  @Test
  public void shouldReplaceNewlinesAndOverlongValuesWithUuid() {
    assertReplacement("abc\ninjected");
    assertReplacement("abc\r\ninjected");
    assertReplacement(repeat('a',CorrelationIdWebFilter.MAX_LENGTH + 1));
  }

  @Test
  public void shouldSetResponseHeaderBeforeAnError() {
    MockServerWebExchange exchange = exchange("/error","error-correlation");

    assertThrows(IllegalStateException.class,() -> filter.filter(
        exchange,ignored -> Mono.error(new IllegalStateException())).block());

    assertEquals("error-correlation",exchange.getResponse().getHeaders()
        .getFirst(CorrelationIdWebFilter.HEADER_NAME));
  }

  @Test
  public void shouldLogEachRequestWithItsOwnIdAndCleanMdc() {
    Logger logger = (Logger) LoggerFactory.getLogger(
        CorrelationIdWebFilter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try {
      filter.filter(exchange("/first","correlation-A"),
          ignored -> Mono.empty()).block();
      assertNull(MDC.get(CorrelationIdWebFilter.MDC_KEY));

      filter.filter(exchange("/second","correlation-B"),
          ignored -> Mono.empty()).block();
      assertNull(MDC.get(CorrelationIdWebFilter.MDC_KEY));

      assertTrue(appender.list.stream()
          .filter(event -> event.getFormattedMessage().contains("/first"))
          .allMatch(event -> "correlation-A".equals(
              event.getMDCPropertyMap().get(CorrelationIdWebFilter.MDC_KEY))));
      assertTrue(appender.list.stream()
          .filter(event -> event.getFormattedMessage().contains("/second"))
          .allMatch(event -> "correlation-B".equals(
              event.getMDCPropertyMap().get(CorrelationIdWebFilter.MDC_KEY))));
      assertFalse(appender.list.stream()
          .filter(event -> event.getFormattedMessage().contains("/second"))
          .anyMatch(event -> "correlation-A".equals(
              event.getMDCPropertyMap().get(CorrelationIdWebFilter.MDC_KEY))));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
      MDC.remove(CorrelationIdWebFilter.MDC_KEY);
    }
  }

  private MockServerWebExchange exchange(String path,String correlationId) {
    MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path);
    if (correlationId != null) {
      request.header(CorrelationIdWebFilter.HEADER_NAME,correlationId);
    }
    return MockServerWebExchange.from(request.build());
  }

  private void assertReplacement(String invalid) {
    String replacement = CorrelationIdWebFilter.resolveCorrelationId(invalid);
    assertFalse(invalid.equals(replacement));
    assertEquals(replacement,UUID.fromString(replacement).toString());
  }

  private String repeat(char value,int length) {
    StringBuilder result = new StringBuilder(length);
    for (int index = 0; index < length; index++) {
      result.append(value);
    }
    return result.toString();
  }
}
