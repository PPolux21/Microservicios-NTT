package tacos.web.api.kitchen;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.Data;

@Data
@Validated
@Component
@ConfigurationProperties(prefix="tacocloud.kitchen")
public class KitchenProperties {

  @NotBlank
  private String stationId;

  @Valid
  private Eta eta = new Eta();

  @Data
  public static class Eta {

    @Min(0)
    private int baseMinutes = 2;

    @Min(0)
    private int minutesPerQueuedOrder = 3;

    @Min(0)
    private int minutesPerItem = 2;

    @Min(0)
    private int minutesPerComplexityPoint = 1;
  }
}
