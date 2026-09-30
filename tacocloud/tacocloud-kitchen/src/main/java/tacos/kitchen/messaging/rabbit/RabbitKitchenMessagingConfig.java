package tacos.kitchen.messaging.rabbit;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import tacos.kitchen.consumer.KitchenConsumerProperties;

@Configuration
@ConditionalOnProperty(prefix="tacocloud.messaging",name="transport",
    havingValue="rabbit")
@EnableConfigurationProperties({RabbitConsumerProperties.class,
    KitchenConsumerProperties.class})
public class RabbitKitchenMessagingConfig {

  @Bean
  public Jackson2JsonMessageConverter rabbitKitchenMessageConverter(
      ObjectMapper objectMapper) {
    return new Jackson2JsonMessageConverter(objectMapper);
  }

  @Bean
  public Queue rabbitOrderQueue(RabbitConsumerProperties properties) {
    return QueueBuilder.durable(properties.getDestination())
        .deadLetterExchange(properties.getDeadLetterExchange())
        .deadLetterRoutingKey(properties.getDeadLetterRoutingKey())
        .build();
  }

  @Bean
  public DirectExchange rabbitOrderDeadLetterExchange(
      RabbitConsumerProperties properties) {
    return new DirectExchange(properties.getDeadLetterExchange(),true,false);
  }

  @Bean
  public Queue rabbitOrderDeadLetterQueue(
      RabbitConsumerProperties properties) {
    return QueueBuilder.durable(properties.getDeadLetterQueue()).build();
  }

  @Bean
  public Binding rabbitOrderDeadLetterBinding(
      RabbitConsumerProperties properties,
      DirectExchange rabbitOrderDeadLetterExchange,
      Queue rabbitOrderDeadLetterQueue) {
    return BindingBuilder.bind(rabbitOrderDeadLetterQueue)
        .to(rabbitOrderDeadLetterExchange)
        .with(properties.getDeadLetterRoutingKey());
  }

  @Bean
  public SimpleRabbitListenerContainerFactory rabbitOrderListenerContainerFactory(
      ConnectionFactory connectionFactory,
      Jackson2JsonMessageConverter rabbitKitchenMessageConverter,
      KitchenConsumerProperties consumerProperties) {
    SimpleRabbitListenerContainerFactory factory =
        new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(rabbitKitchenMessageConverter);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setDefaultRequeueRejected(false);
    factory.setConcurrentConsumers(consumerProperties.getConcurrency());
    return factory;
  }
}
