package tacos.kitchen.messaging.rabbit;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import com.rabbitmq.client.GetResponse;

@Service
@ConditionalOnProperty(prefix="tacocloud.messaging",name="transport",
    havingValue="rabbit")
public class RabbitOrderDlqReplayService {

  private final RabbitTemplate rabbit;
  private final RabbitConsumerProperties properties;

  public RabbitOrderDlqReplayService(RabbitTemplate rabbit,
      RabbitConsumerProperties properties) {
    this.rabbit = rabbit;
    this.properties = properties;
  }

  public Mono<Boolean> replayOne() {
    return Mono.fromCallable(() -> rabbit.execute(channel -> {
      GetResponse message = channel.basicGet(
          properties.getDeadLetterQueue(),false);
      if (message == null) {
        return false;
      }
      long deliveryTag = message.getEnvelope().getDeliveryTag();
      try {
        channel.basicPublish("",properties.getDestination(),
            message.getProps(),message.getBody());
        channel.basicAck(deliveryTag,false);
        return true;
      } catch (Exception failure) {
        channel.basicNack(deliveryTag,false,true);
        throw failure;
      }
    }))
        .subscribeOn(Schedulers.boundedElastic())
        .map(Boolean.TRUE::equals);
  }
}
