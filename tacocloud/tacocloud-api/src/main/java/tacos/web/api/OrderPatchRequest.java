package tacos.web.api;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Data;

@Data
public class OrderPatchRequest {

  private String deliveryName;
  private String deliveryStreet;
  private String deliveryCity;
  private String deliveryState;
  private String deliveryZip;

  private final Map<String, Object> unsupportedFields =
      new HashMap<>();

  @JsonAnySetter
  public void addUnsupportedField(
      String name,
      Object value) {

    unsupportedFields.put(name, value);
  }

  public boolean hasUnsupportedFields() {
    return !unsupportedFields.isEmpty();
  }
}