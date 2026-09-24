package tacos.web.api.error;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiProblem {

  private String type;

  private String title;

  private int status;

  private String detail;

  private String instance;

  private String code;

  private List<Violation> violations =
      new ArrayList<>();


  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Violation {

    private String field;

    private String reason;
  }
}