package tacos.kitchen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import tacos.messaging.OrderEvent;

@Component
@Slf4j
public class KitchenUI {

  private final RestTemplate api;
  private final String apiBaseUrl;

  @Autowired
  public KitchenUI(RestTemplateBuilder builder,
      @Value("${tacocloud.kitchen.api-base-url}") String apiBaseUrl,
      @Value("${tacocloud.kitchen.username}") String username,
      @Value("${tacocloud.kitchen.password}") String password) {
    this(builder
        .requestFactory(HttpComponentsClientHttpRequestFactory::new)
        .basicAuthentication(username,password)
        .build(),apiBaseUrl);
  }

  KitchenUI(RestTemplate api,String apiBaseUrl) {
    this.api = api;
    this.apiBaseUrl = apiBaseUrl.replaceAll("/+$","");
  }

  public List<KitchenOrderView> loadQueue() {
    KitchenOrderView[] queue = api.getForObject(
        apiBaseUrl + "/api/v1/kitchen/queue",KitchenOrderView[].class);
    if (queue == null) {
      return Collections.emptyList();
    }
    List<KitchenOrderView> result = new ArrayList<>();
    Collections.addAll(result,queue);
    return result;
  }

  public KitchenOrderView claimNext() {
    return api.postForObject(
        apiBaseUrl + "/api/v1/kitchen/orders/claim",null,
        KitchenOrderView.class);
  }

  public KitchenOrderView advance(String orderId,String status) {
    HttpEntity<Map<String,String>> request = new HttpEntity<>(
        Collections.singletonMap("status",status));
    return api.exchange(
        apiBaseUrl + "/api/v1/orders/" + orderId + "/status",
        HttpMethod.PATCH,request,KitchenOrderView.class).getBody();
  }

  public void displayOrder(OrderEvent event) {
    int itemCount = event != null && event.getPayload() != null
        ? event.getPayload().getItems().stream()
            .mapToInt(item -> item.getQuantity()).sum() : 0;
    log.info("Received kitchen order eventId={} orderId={} type={} itemCount={}",
        event != null ? event.getEventId() : null,
        event != null && event.getPayload() != null
            ? event.getPayload().getOrderId() : null,
        event != null ? event.getEventType() : null,itemCount);
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown=true)
  public static class KitchenOrderView {
    private String orderId;
    private Date placedAt;
    private String status;
    private List<KitchenItemView> items = new ArrayList<>();
    private String stationId;
    private String cookId;
    private int estimatedPrepMinutes;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown=true)
  public static class KitchenItemView {
    private String tacoName;
    private List<String> ingredientIds = new ArrayList<>();
    private int quantity;
  }
}
