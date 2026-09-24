package tacos.web.api;

import java.util.HashMap;
import java.util.Map;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Data;

@Data
public class OrderDeliveryRequest {

  @Size(max=50,message="deliveryName must have at most 50 characters")
  private String deliveryName;

  @Size(max=100,message="deliveryStreet must have at most 100 characters")
  private String deliveryStreet;

  @Size(max=50,message="deliveryCity must have at most 50 characters")
  private String deliveryCity;

  @Size(min=2,max=50,message="deliveryState must have between 2 and 50 characters")
  private String deliveryState;

  @Pattern(regexp="[A-Za-z0-9 -]{3,10}",message="deliveryZip has an invalid format")
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