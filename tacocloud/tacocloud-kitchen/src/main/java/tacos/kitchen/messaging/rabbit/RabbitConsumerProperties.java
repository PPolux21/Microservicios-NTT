package tacos.kitchen.messaging.rabbit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="tacocloud.messaging.rabbit")
public class RabbitConsumerProperties {

  private String destination;
  private String deadLetterExchange;
  private String deadLetterRoutingKey;
  private String deadLetterQueue;

  public String getDestination() { return destination; }
  public void setDestination(String destination) { this.destination = destination; }
  public String getDeadLetterExchange() { return deadLetterExchange; }
  public void setDeadLetterExchange(String deadLetterExchange) {
    this.deadLetterExchange = deadLetterExchange;
  }
  public String getDeadLetterRoutingKey() { return deadLetterRoutingKey; }
  public void setDeadLetterRoutingKey(String deadLetterRoutingKey) {
    this.deadLetterRoutingKey = deadLetterRoutingKey;
  }
  public String getDeadLetterQueue() { return deadLetterQueue; }
  public void setDeadLetterQueue(String deadLetterQueue) {
    this.deadLetterQueue = deadLetterQueue;
  }
}
