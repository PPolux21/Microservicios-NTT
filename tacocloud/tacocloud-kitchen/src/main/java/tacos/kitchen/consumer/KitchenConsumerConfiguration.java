package tacos.kitchen.consumer;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;

import io.micrometer.core.instrument.MeterRegistry;
import tacos.actuator.TacoMetrics;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.data.outbox.OutboxEventRepository;
import tacos.web.api.InventoryService;
import tacos.web.api.OrderWorkflowService;
import tacos.web.api.outbox.MongoTransactionConfiguration;
import tacos.web.api.outbox.OrderOutboxService;

@Configuration
@EnableReactiveMongoRepositories(basePackages="tacos.data")
@Import(MongoTransactionConfiguration.class)
public class KitchenConsumerConfiguration {

  @Bean
  Clock kitchenConsumerClock() {
    return Clock.systemUTC();
  }

  @Bean
  TacoMetrics kitchenTacoMetrics(MeterRegistry registry) {
    return new TacoMetrics(registry);
  }

  @Bean
  InventoryService kitchenInventoryService(ReactiveMongoTemplate mongo,
      TacoMetrics metrics) {
    return new InventoryService(mongo,metrics);
  }

  @Bean
  OrderOutboxService kitchenOrderOutboxService(OrderRepository orders,
      OutboxEventRepository outbox,
      org.springframework.transaction.reactive.TransactionalOperator transactions,
      Clock clock,TacoMetrics metrics) {
    return new OrderOutboxService(orders,outbox,transactions,clock,metrics);
  }

  @Bean
  OrderWorkflowService kitchenOrderWorkflowService(OrderRepository orders,
      UserRepository users,InventoryService inventory,Clock clock,
      OrderOutboxService orderOutbox,TacoMetrics metrics) {
    return new OrderWorkflowService(
        orders,users,inventory,clock,orderOutbox,metrics);
  }
}
