package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.User;
import tacos.web.api.OrderService.OrderHistoryPage;
import tacos.web.api.dto.ApiDtos.OrderHistoryQuery;

import com.fasterxml.jackson.databind.ObjectMapper;

public class OrderHistoryControllerTest {

  @Test
  public void shouldReturnPagedSummaryAndSafeDetail() throws Exception {
    OrderService service = org.mockito.Mockito.mock(OrderService.class);
    OrderHistoryController controller = new OrderHistoryController(service);
    TacoOrder order = orderWithSensitiveOwner();

    when(service.findOwnOrderHistory(any(Authentication.class),
        org.mockito.ArgumentMatchers.eq(0),org.mockito.ArgumentMatchers.eq(20)))
        .thenReturn(Mono.just(new OrderHistoryPage(
            Collections.singletonList(order),0,20,1)));
    when(service.findOwnOrder(org.mockito.ArgumentMatchers.eq("ORDER-1"),
        any(Authentication.class))).thenReturn(Mono.just(order));

    Authentication authentication = authentication();
    OrderHistoryQuery query = new OrderHistoryQuery();
    StepVerifier.create(controller.ownOrders(query,authentication))
        .assertNext(page -> {
          org.junit.jupiter.api.Assertions.assertEquals(
              "ORDER-1",page.getItems().get(0).getId());
          org.junit.jupiter.api.Assertions.assertEquals(1,page.getTotalElements());
        })
        .verifyComplete();

    String json = new ObjectMapper().writeValueAsString(
        controller.ownOrder("ORDER-1",authentication).block());
    org.junit.jupiter.api.Assertions.assertFalse(json.contains("\"user\""));
    org.junit.jupiter.api.Assertions.assertFalse(json.contains("password"));
    org.junit.jupiter.api.Assertions.assertFalse(json.contains("paymentToken"));
    org.junit.jupiter.api.Assertions.assertFalse(json.contains("ccNumber"));
    org.junit.jupiter.api.Assertions.assertFalse(json.contains("ccCVV"));
    org.junit.jupiter.api.Assertions.assertFalse(json.contains("ccExpiration"));
  }

  private TacoOrder orderWithSensitiveOwner() {
    User owner = new User("alice","{noop}secret","Alice","street","city",
        "state","00000","000","alice@example.test");
    owner.setId("U-A");
    TacoOrder order = new TacoOrder();
    order.setId("ORDER-1");
    order.setUser(owner);
    order.setTotal(new BigDecimal("25.00"));
    return order;
  }

  private Authentication authentication() {
    return org.mockito.Mockito.mock(Authentication.class);
  }
}
