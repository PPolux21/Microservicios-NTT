package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.web.api.OrderService.OrderHistoryPage;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

@SpringBootTest(
    classes=OrderHistoryMongoIntegrationTest.TestApplication.class,
    webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={
      "spring.data.mongodb.database=tc23_order_history_test",
      "spring.data.mongodb.auto-index-creation=true"
    })
public class OrderHistoryMongoIntegrationTest {

  @Autowired private OrderService service;
  @Autowired private OrderRepository orderRepo;
  @Autowired private UserRepository userRepo;
  @Autowired private ReactiveMongoTemplate mongo;

  @BeforeEach
  public void clean() {
    mongo.remove(new Query(),TacoOrder.class)
        .then(mongo.remove(new Query(),User.class)).block();
  }

  @Test
  public void shouldIsolateOrderStableAndPaginateIncludingOutOfRange() {
    Authentication alice = authentication("alice");
    Mono<List<OrderHistoryPage>> scenario = seedUser("U-A","alice")
        .zipWith(seedUser("U-B","bob"))
        .flatMap(users -> Mono.when(
            saveOrder("A-2",users.getT1(),"2026-09-29T12:00:00Z"),
            saveOrder("A-1",users.getT1(),"2026-09-29T12:00:00Z"),
            saveOrder("A-0",users.getT1(),"2026-09-01T12:00:00Z"),
            saveOrder("B-1",users.getT2(),"2026-09-30T12:00:00Z")))
        .then(Mono.zip(
            service.findOwnOrderHistory(alice,0,2),
            service.findOwnOrderHistory(alice,1,2),
            service.findOwnOrderHistory(alice,100,2)))
        .map(tuple -> Arrays.asList(tuple.getT1(),tuple.getT2(),tuple.getT3()));

    StepVerifier.create(scenario)
        .assertNext(pages -> {
          assertEquals(Arrays.asList("A-1","A-2"),ids(pages.get(0)));
          assertEquals(Arrays.asList("A-0"),ids(pages.get(1)));
          assertTrue(pages.get(2).getItems().isEmpty());
          assertEquals(3,pages.get(0).getTotalElements());
          assertEquals(2,pages.get(0).getTotalPages());
        })
        .verifyComplete();

    StepVerifier.create(mongo.findById("A-1",Document.class,"tacoOrder"))
        .assertNext(document -> {
          assertEquals("U-A",document.getString("userId"));
          assertTrue(!document.containsKey("user"));
        })
        .verifyComplete();
  }

  @Test
  public void shouldHideForeignOrderAndCreateHistoryIndex() {
    Authentication alice = authentication("alice");
    Mono<Void> seed = seedUser("U-A","alice")
        .zipWith(seedUser("U-B","bob"))
        .flatMap(users -> saveOrder(
            "B-ORDER",users.getT2(),"2026-09-29T12:00:00Z"));

    StepVerifier.create(seed.then(service.findOwnOrder("B-ORDER",alice)))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof ApiException);
          assertEquals(404,((ApiException) error).getStatus().value());
        })
        .verify();

    StepVerifier.create(mongo.indexOps(TacoOrder.class).getIndexInfo()
        .filter(index -> "order_user_placed_id_idx".equals(index.getName()))
        .single())
        .assertNext(index -> assertTrue(!index.getIndexFields().isEmpty()))
        .verifyComplete();
  }

  private Mono<User> seedUser(String id,String username) {
    User user = new User(username,"{noop}secret",username,"street","city",
        "state","00000","000",username + "@example.test");
    user.setId(id);
    return userRepo.save(user);
  }

  private Mono<Void> saveOrder(String id,User user,String placedAt) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setUser(user);
    order.setPlacedAt(Date.from(Instant.parse(placedAt)));
    order.setTotal(new BigDecimal("10.00"));
    return orderRepo.save(order).then();
  }

  private Authentication authentication(String username) {
    return new UsernamePasswordAuthenticationToken(
        username,"password",AuthorityUtils.createAuthorityList("ROLE_USER"));
  }

  private List<String> ids(OrderHistoryPage page) {
    return page.getItems().stream().map(TacoOrder::getId)
        .collect(Collectors.toList());
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableReactiveMongoRepositories(basePackageClasses=OrderRepository.class)
  @Import(TestConfig.class)
  static class TestApplication {
  }

  static class TestConfig {
    @Bean
    OrderService orderService(OrderRepository orders,UserRepository users) {
      return new OrderService(
          orders,null,null,users,null,null,null,null,null,null,null,null);
    }
  }
}
