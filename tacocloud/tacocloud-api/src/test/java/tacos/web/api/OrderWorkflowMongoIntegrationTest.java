package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.actuator.TacoMetrics;
import tacos.TacoOrder.Status;
import tacos.data.OrderRepository;
import tacos.web.api.outbox.OrderOutboxService;

@SpringBootTest(
    classes=OrderWorkflowMongoIntegrationTest.TestApplication.class,
    webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={
      "spring.data.mongodb.database=tc25_order_workflow_test",
      "spring.data.mongodb.auto-index-creation=true"
    })
public class OrderWorkflowMongoIntegrationTest {

  @Autowired private OrderRepository orders;
  @Autowired private OrderWorkflowService workflow;
  @Autowired private ReactiveMongoTemplate mongo;

  @BeforeEach
  public void clean() {
    mongo.remove(new Query(),TacoOrder.class).block();
  }

  @Test
  public void shouldRejectARealStaleMongoVersionAndKeepFirstTransition() {
    TacoOrder initial = new TacoOrder();
    initial.setId("ORDER-LOCK");
    initial.setUserId("OWNER");
    initial.setStatus(Status.CREATED);

    Mono<Void> staleWrite = orders.save(initial)
        .then(Mono.zip(
            orders.findById("ORDER-LOCK"),
            orders.findById("ORDER-LOCK")))
        .flatMap(copies -> workflow.transition(
            "ORDER-LOCK",Status.ACCEPTED,"accepted",kitchen())
            .then(Mono.defer(() -> {
              TacoOrder stale = copies.getT2();
              stale.setStatus(Status.PREPARING);
              return orders.save(stale);
            })))
        .then();

    StepVerifier.create(staleWrite)
        .expectError(OptimisticLockingFailureException.class)
        .verify();

    StepVerifier.create(orders.findById("ORDER-LOCK"))
        .assertNext(saved -> {
          assertEquals(Status.ACCEPTED,saved.getStatus());
          assertNotNull(saved.getVersion());
          assertEquals(1,saved.getStatusHistory().size());
          assertEquals("cook",saved.getStatusHistory().get(0).getChangedBy());
        })
        .verifyComplete();
  }

  private Authentication kitchen() {
    return new UsernamePasswordAuthenticationToken(
        "cook","password",AuthorityUtils.createAuthorityList("ROLE_KITCHEN"));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableReactiveMongoRepositories(basePackageClasses=OrderRepository.class)
  @Import({OrderWorkflowService.class,TestConfig.class,TacoMetrics.class})
  static class TestApplication {
  }

  static class TestConfig {
    @Bean
    Clock clock() {
      return Clock.fixed(
          Instant.parse("2026-09-29T18:00:00Z"),ZoneOffset.UTC);
    }

    @Bean
    InventoryService inventoryService() {
      return Mockito.mock(InventoryService.class);
    }

    @Bean
    OrderOutboxService orderOutboxService(OrderRepository orders) {
      OrderOutboxService service = Mockito.mock(OrderOutboxService.class);
      Mockito.when(service.saveStatusChanged(
          Mockito.any(TacoOrder.class),Mockito.any(Status.class),
          Mockito.anyString(),Mockito.nullable(String.class)))
          .thenAnswer(invocation -> orders.save(invocation.getArgument(0)));
      return service;
    }
  }
}
