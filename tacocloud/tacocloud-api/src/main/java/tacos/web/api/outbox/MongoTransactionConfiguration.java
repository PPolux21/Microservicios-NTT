package tacos.web.api.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
public class MongoTransactionConfiguration {

  @Bean
  ReactiveMongoTransactionManager reactiveMongoTransactionManager(
      ReactiveMongoDatabaseFactory databaseFactory) {
    return new ReactiveMongoTransactionManager(databaseFactory);
  }

  @Bean
  TransactionalOperator transactionalOperator(
      ReactiveTransactionManager transactionManager) {
    return TransactionalOperator.create(transactionManager);
  }
}
