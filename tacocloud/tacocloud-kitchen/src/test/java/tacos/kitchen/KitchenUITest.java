package tacos.kitchen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import tacos.kitchen.KitchenUI.KitchenOrderView;

public class KitchenUITest {

  @Test
  public void shouldUseQueueClaimAndExistingStatusEndpoints() {
    RestTemplate rest = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
    KitchenUI ui = new KitchenUI(rest,"http://api.test/");
    String order = "{\"orderId\":\"ORDER-1\",\"status\":\"ACCEPTED\","
        + "\"stationId\":\"STATION-A\",\"cookId\":\"cook-a\","
        + "\"estimatedPrepMinutes\":9,\"items\":[]}";

    server.expect(once(),requestTo("http://api.test/api/kitchen/queue"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[" + order + "]",MediaType.APPLICATION_JSON));
    server.expect(once(),requestTo("http://api.test/api/kitchen/orders/claim"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(order,MediaType.APPLICATION_JSON));
    server.expect(once(),requestTo("http://api.test/api/orders/ORDER-1/status"))
        .andExpect(method(HttpMethod.PATCH))
        .andExpect(content().json("{\"status\":\"PREPARING\"}"))
        .andRespond(withSuccess(
            "{\"orderId\":\"ORDER-1\",\"status\":\"PREPARING\","
                + "\"stationId\":\"STATION-A\",\"cookId\":\"cook-a\"}",
            MediaType.APPLICATION_JSON));

    List<KitchenOrderView> queue = ui.loadQueue();
    KitchenOrderView claimed = ui.claimNext();
    KitchenOrderView preparing = ui.advance("ORDER-1","PREPARING");

    assertEquals(1,queue.size());
    assertEquals(9,queue.get(0).getEstimatedPrepMinutes());
    assertNotNull(claimed);
    assertEquals("ACCEPTED",claimed.getStatus());
    assertEquals("PREPARING",preparing.getStatus());
    server.verify();
  }
}
