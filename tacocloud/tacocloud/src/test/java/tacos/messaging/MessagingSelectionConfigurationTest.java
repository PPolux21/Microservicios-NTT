package tacos.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

class MessagingSelectionConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              NoopMessagingConfig.class,
              JmsMessagingConfig.class,
              RabbitMessagingConfig.class,
              KafkaMessagingConfig.class,
              MessagingSelectionConfiguration.class)
          .withBean(ObjectMapper.class,ObjectMapper::new)
          .withBean(JmsTemplate.class,() -> mock(JmsTemplate.class))
          .withBean(RabbitTemplate.class,() -> mock(RabbitTemplate.class))
          .withBean(KafkaTemplate.class,() -> mock(KafkaTemplate.class));

  @Test
  void selectsNoopAndOnlyNoop() {
    contextRunner
        .withPropertyValues("tacocloud.messaging.transport=noop")
        .run(context -> assertOnly(context.getBeansOfType(
            OrderMessagingService.class),NoOpOrderMessagingService.class));
  }

  @Test
  void selectsJmsAndOnlyJms() {
    contextRunner
        .withPropertyValues(
            "tacocloud.messaging.transport=jms",
            "tacocloud.messaging.jms.destination=orders-jms")
        .run(context -> assertOnly(context.getBeansOfType(
            OrderMessagingService.class),JmsOrderMessagingService.class));
  }

  @Test
  void selectsRabbitAndOnlyRabbit() {
    contextRunner
        .withPropertyValues(
            "tacocloud.messaging.transport=rabbit",
            "tacocloud.messaging.rabbit.destination=orders-rabbit")
        .run(context -> assertOnly(context.getBeansOfType(
            OrderMessagingService.class),RabbitOrderMessagingService.class));
  }

  @Test
  void selectsKafkaAndOnlyKafka() {
    contextRunner
        .withPropertyValues(
            "tacocloud.messaging.transport=kafka",
            "tacocloud.messaging.kafka.topic=orders-kafka")
        .run(context -> assertOnly(context.getBeansOfType(
            OrderMessagingService.class),KafkaOrderMessagingService.class));
  }

  @Test
  void defaultsToNoopForLocalDevelopment() {
    contextRunner.run(context -> assertOnly(context.getBeansOfType(
        OrderMessagingService.class),NoOpOrderMessagingService.class));
  }

  @Test
  void defaultsToNoopForDevAndTestProfiles() {
    for (String profile : new String[] {"dev","test"}) {
      contextRunner
          .withPropertyValues("spring.profiles.active=" + profile)
          .run(context -> assertOnly(context.getBeansOfType(
              OrderMessagingService.class),NoOpOrderMessagingService.class));
    }
  }

  @Test
  void rejectsInvalidTransportWithAllowedValues() {
    contextRunner
        .withPropertyValues("tacocloud.messaging.transport=activemq2")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasMessage(
                  "Unsupported tacocloud.messaging.transport='activemq2'. "
                      + "Allowed values: noop, jms, rabbit, kafka.");
        });
  }

  @Test
  void requiresExplicitTransportInProduction() {
    contextRunner
        .withPropertyValues("spring.profiles.active=prod")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasMessage(
                  "tacocloud.messaging.transport is required outside dev/test. "
                      + "Allowed values: noop, jms, rabbit, kafka.");
        });
  }

  @Test
  void rejectsNoopInProductionEvenWhenExplicit() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=prod",
            "tacocloud.messaging.transport=noop")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasMessage(
                  "Transport noop is allowed only for local dev/test. "
                      + "Choose one of: jms, rabbit, kafka.");
        });
  }

  @Test
  void acceptsExplicitBrokerInProduction() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=prod",
            "tacocloud.messaging.transport=kafka",
            "tacocloud.messaging.kafka.topic=orders-prod")
        .run(context -> assertOnly(context.getBeansOfType(
            OrderMessagingService.class),KafkaOrderMessagingService.class));
  }

  @Test
  void rejectsMoreThanOnePortImplementation() {
    contextRunner
        .withPropertyValues("tacocloud.messaging.transport=noop")
        .withBean("unexpectedOrderMessagingService",
            OrderMessagingService.class,() -> event -> { })
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasMessage(
                  "Expected exactly one OrderMessagingService for "
                      + "tacocloud.messaging.transport='noop' but found 2.");
        });
  }

  @Test
  void usesExternallyConfiguredDestination() {
    assertJmsDestination("orders-test-a");
    assertJmsDestination("orders-test-b");
  }

  private void assertJmsDestination(String destination) {
    contextRunner
        .withPropertyValues(
            "tacocloud.messaging.transport=jms",
            "tacocloud.messaging.jms.destination=" + destination)
        .run(context -> {
          OrderEvent event = fixedEvent();
          context.getBean(OrderMessagingService.class).sendOrder(event);
          verify(context.getBean(JmsTemplate.class)).convertAndSend(
              eq(destination),same(event),any(MessagePostProcessor.class));
        });
  }

  @Test
  void versionedRuntimeConfigurationContainsNoLegacyBrokerSecrets()
      throws IOException {
    String yaml = new String(new ClassPathResource("application.yml")
        .getInputStream().readAllBytes(),StandardCharsets.UTF_8);

    assertThat(yaml)
        .doesNotContain("letm31n","l3tm31n","tacoweb")
        .contains("${ARTEMIS_PASSWORD:guest}")
        .contains("${RABBIT_PASSWORD:guest}");
  }

  private void assertOnly(Map<String,OrderMessagingService> services,
      Class<? extends OrderMessagingService> implementation) {
    assertThat(services).hasSize(1);
    assertThat(services.values().iterator().next()).isInstanceOf(implementation);
  }

  private OrderEvent fixedEvent() {
    return new OrderEvent(
        UUID.fromString("00000000-0000-0000-0000-000000000028"),
        OrderEventType.ORDER_CREATED,1,
        Instant.parse("2026-09-29T20:28:00Z"),"corr-tc28",
        new OrderEventPayload("ORDER-TC28","CREATED",null,
            Instant.parse("2026-09-29T20:27:00Z"),
            Collections.emptyList(),null,null,null));
  }
}
