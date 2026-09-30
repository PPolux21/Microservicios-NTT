package tacos.kitchen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import tacos.TacoOrder;

@Component
@Slf4j
public class KitchenUI {

  private final RestTemplate api;
  private final String apiBaseUrl;

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
        apiBaseUrl + "/api/kitchen/queue",KitchenOrderView[].class);
    if (queue == null) {
      return Collections.emptyList();
    }
    List<KitchenOrderView> result = new ArrayList<>();
    Collections.addAll(result,queue);
    return result;
  }

  public KitchenOrderView claimNext() {
    return api.postForObject(
        apiBaseUrl + "/api/kitchen/orders/claim",null,
        KitchenOrderView.class);
  }

  public KitchenOrderView advance(String orderId,String status) {
    HttpEntity<Map<String,String>> request = new HttpEntity<>(
        Collections.singletonMap("status",status));
    return api.exchange(
        apiBaseUrl + "/api/orders/" + orderId + "/status",
        HttpMethod.PATCH,request,KitchenOrderView.class).getBody();
  }

  public void displayOrder(TacoOrder order) {
    int tacoCount = order != null && order.getTacos() != null
        ? order.getTacos().size() : 0;
    log.info("Received kitchen order placedAt={} tacoCount={}",
        order != null ? order.getPlacedAt() : null,tacoCount);
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
