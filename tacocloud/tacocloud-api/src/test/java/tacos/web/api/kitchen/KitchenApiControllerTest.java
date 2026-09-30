package tacos.web.api.kitchen;

import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.web.api.dto.ApiDtos.KitchenOrderItemResponse;
import tacos.web.api.dto.ApiDtos.KitchenOrderResponse;

public class KitchenApiControllerTest {

  @Mock
  private KitchenQueueService service;

  private WebTestClient client;

  @BeforeEach
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    client = WebTestClient.bindToController(
        new KitchenApiController(service)).build();
  }

  @Test
  public void shouldReturnSafeQueueAndClaim() {
    KitchenOrderResponse order = order();
    when(service.queue(null)).thenReturn(Flux.just(order));
    when(service.claimNext(null)).thenReturn(Mono.just(order));

    client.get().uri("/api/kitchen/queue")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$[0].orderId").isEqualTo("ORDER-1")
        .jsonPath("$[0].estimatedPrepMinutes").isEqualTo(9)
        .jsonPath("$[0].deliveryStreet").doesNotExist()
        .jsonPath("$[0].paymentToken").doesNotExist()
        .jsonPath("$[0].userId").doesNotExist();

    client.post().uri("/api/kitchen/orders/claim")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("ACCEPTED")
        .jsonPath("$.stationId").isEqualTo("STATION-A")
        .jsonPath("$.cookId").isEqualTo("cook-a")
        .jsonPath("$.deliveryZip").doesNotExist();
  }

  @Test
  public void shouldReturnNoContentWhenQueueIsEmpty() {
    when(service.claimNext(null)).thenReturn(Mono.empty());

    client.post().uri("/api/kitchen/orders/claim")
        .exchange()
        .expectStatus().isNoContent()
        .expectBody().isEmpty();
  }

  private KitchenOrderResponse order() {
    KitchenOrderItemResponse item = new KitchenOrderItemResponse(
        "Safe Taco",Arrays.asList("FLTO","BEAN"),2);
    return new KitchenOrderResponse(
        "ORDER-1",new Date(),"ACCEPTED",Collections.singletonList(item),
        "STATION-A","cook-a",9);
  }
}
