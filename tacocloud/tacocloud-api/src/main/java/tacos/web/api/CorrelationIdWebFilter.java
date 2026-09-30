package tacos.web.api;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

  public static final String HEADER_NAME = "X-Correlation-Id";
  public static final String CONTEXT_KEY = "correlationId";
  public static final String MDC_KEY = "correlationId";
  static final int MAX_LENGTH = 64;

  private static final Pattern VALID_VALUE =
      Pattern.compile("[A-Za-z0-9._-]{1," + MAX_LENGTH + "}");
  private static final Logger LOGGER =
      LoggerFactory.getLogger(CorrelationIdWebFilter.class);

  @Override
  public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
    String correlationId = resolveCorrelationId(
        exchange.getRequest().getHeaders().getFirst(HEADER_NAME));
    exchange.getResponse().getHeaders().set(HEADER_NAME,correlationId);

    withMdc(correlationId,() -> LOGGER.info(
        "HTTP request started method={} path={}",
        exchange.getRequest().getMethod(),exchange.getRequest().getPath()));

    return chain.filter(exchange)
        .doOnSuccess(ignored -> withMdc(correlationId,() -> LOGGER.info(
            "HTTP request completed method={} path={} status={}",
            exchange.getRequest().getMethod(),exchange.getRequest().getPath(),
            exchange.getResponse().getStatusCode())))
        .doOnError(error -> withMdc(correlationId,() -> LOGGER.warn(
            "HTTP request failed method={} path={} errorType={}",
            exchange.getRequest().getMethod(),exchange.getRequest().getPath(),
            error.getClass().getSimpleName())))
        .contextWrite(context -> context.put(CONTEXT_KEY,correlationId));
  }

  public static Mono<String> currentCorrelationId() {
    return Mono.deferContextual(context -> Mono.just(
        context.hasKey(CONTEXT_KEY)
            ? context.<String>get(CONTEXT_KEY)
            : UUID.randomUUID().toString()));
  }

  static String resolveCorrelationId(String candidate) {
    return candidate != null && VALID_VALUE.matcher(candidate).matches()
        ? candidate : UUID.randomUUID().toString();
  }

  private static void withMdc(String correlationId,Runnable action) {
    String previous = MDC.get(MDC_KEY);
    try {
      MDC.put(MDC_KEY,correlationId);
      action.run();
    } finally {
      if (previous == null) {
        MDC.remove(MDC_KEY);
      } else {
        MDC.put(MDC_KEY,previous);
      }
    }
  }
}
