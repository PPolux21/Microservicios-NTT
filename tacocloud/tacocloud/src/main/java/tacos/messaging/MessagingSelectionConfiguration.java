package tacos.messaging;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class MessagingSelectionConfiguration {

  static final String TRANSPORT_PROPERTY = "tacocloud.messaging.transport";
  static final String ALLOWED_TRANSPORTS = "noop, jms, rabbit, kafka";
  private static final List<String> ALLOWED =
      Arrays.asList("noop","jms","rabbit","kafka");

  @Bean
  SmartInitializingSingleton validateMessagingTransport(Environment environment,
      ObjectProvider<OrderMessagingService> services) {
    return () -> {
      String configured = environment.getProperty(TRANSPORT_PROPERTY);
      boolean missing = configured == null || configured.trim().isEmpty();
      String transport = missing ? "noop"
          : configured.trim().toLowerCase(Locale.ROOT);

      if (!ALLOWED.contains(transport)) {
        throw new IllegalStateException(
            "Unsupported " + TRANSPORT_PROPERTY + "='" + configured
                + "'. Allowed values: " + ALLOWED_TRANSPORTS + ".");
      }

      boolean localProfile = isLocalProfile(environment);
      if (missing && !localProfile) {
        throw new IllegalStateException(
            TRANSPORT_PROPERTY + " is required outside dev/test. Allowed values: "
                + ALLOWED_TRANSPORTS + ".");
      }
      if ("noop".equals(transport) && !localProfile) {
        throw new IllegalStateException(
            "Transport noop is allowed only for local dev/test. Choose one of: "
                + "jms, rabbit, kafka.");
      }

      List<OrderMessagingService> selected = services.orderedStream()
          .collect(Collectors.toList());
      if (selected.size() != 1) {
        throw new IllegalStateException(
            "Expected exactly one OrderMessagingService for "
                + TRANSPORT_PROPERTY + "='" + transport + "' but found "
                + selected.size() + ".");
      }
    };
  }

  private boolean isLocalProfile(Environment environment) {
    String[] activeProfiles = environment.getActiveProfiles();
    if (activeProfiles.length == 0) {
      return true;
    }
    boolean local = false;
    for (String profile : activeProfiles) {
      if ("prod".equals(profile) || "production".equals(profile)
          || "integration".equals(profile)) {
        return false;
      }
      if ("dev".equals(profile) || "test".equals(profile)) {
        local = true;
      }
    }
    return local;
  }
}
