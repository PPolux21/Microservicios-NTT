package tacos.web.api.kitchen;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.web.api.dto.ApiDtos.KitchenOrderResponse;

@RestController
@RequestMapping(
    path={"/api/kitchen","/api/v1/kitchen"},
    produces="application/json")
public class KitchenApiController {

  private final KitchenQueueService kitchenQueue;

  public KitchenApiController(KitchenQueueService kitchenQueue) {
    this.kitchenQueue = kitchenQueue;
  }

  @GetMapping("/queue")
  public Mono<List<KitchenOrderResponse>> queue(
      Authentication authentication) {
    return kitchenQueue.queue(authentication).collectList();
  }

  @PostMapping("/orders/claim")
  public Mono<ResponseEntity<KitchenOrderResponse>> claim(
      Authentication authentication) {
    return kitchenQueue.claimNext(authentication)
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.noContent().build());
  }
}
