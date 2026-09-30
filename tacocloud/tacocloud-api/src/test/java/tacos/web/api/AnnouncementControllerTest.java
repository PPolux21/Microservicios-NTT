package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import javax.validation.Validation;
import javax.validation.Validator;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.Authentication;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.OpsAnnouncement;
import tacos.OpsAnnouncement.Severity;
import tacos.web.api.AnnouncementController.AnnouncementRequest;

public class AnnouncementControllerTest {

  private static final Instant CREATED_AT =
      Instant.parse("2026-09-30T12:00:00Z");
  private static final Instant EXPIRES_AT =
      Instant.parse("2026-10-01T12:00:00Z");

  @Test
  public void shouldUseAuthenticatedAuthorAndKeepItOutOfResponse()
      throws Exception {
    AnnouncementService service = Mockito.mock(AnnouncementService.class);
    AnnouncementController controller = new AnnouncementController(service);
    Authentication authentication = Mockito.mock(Authentication.class);
    when(authentication.getName()).thenReturn("real-admin");
    when(service.create(
        "Maintenance",Severity.WARNING,EXPIRES_AT,"real-admin"))
        .thenReturn(Mono.just(announcement()));

    AnnouncementRequest request = new AnnouncementRequest();
    request.setText("Maintenance");
    request.setSeverity(Severity.WARNING);
    request.setExpiresAt(EXPIRES_AT);

    StepVerifier.create(controller.create(request,authentication))
        .assertNext(response -> {
          assertEquals("A-1",response.getId());
          try {
            String json = new ObjectMapper()
                .findAndRegisterModules().writeValueAsString(response);
            assertFalse(json.contains("createdBy"));
            assertFalse(json.contains("real-admin"));
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        })
        .verifyComplete();

    verify(service).create(
        "Maintenance",Severity.WARNING,EXPIRES_AT,"real-admin");
  }

  @Test
  public void shouldRejectUnknownSeverityAndOversizedTextAtContractBoundary() {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
        () -> mapper.readValue(
            "{\"text\":\"Maintenance\","
                + "\"severity\":\"SUPER_MEGA_IMPORTANT\","
                + "\"expiresAt\":\"2026-10-01T12:00:00Z\"}",
            AnnouncementRequest.class));

    AnnouncementRequest oversized = new AnnouncementRequest();
    oversized.setText(
        "x".repeat(AnnouncementService.MAX_TEXT_LENGTH + 1));
    oversized.setSeverity(Severity.INFO);
    oversized.setExpiresAt(EXPIRES_AT);
    Validator validator = Validation.buildDefaultValidatorFactory()
        .getValidator();
    assertTrue(validator.validate(oversized).stream()
        .anyMatch(violation -> "text".equals(
            violation.getPropertyPath().toString())));
  }

  private OpsAnnouncement announcement() {
    return new OpsAnnouncement(
        "A-1","Maintenance",Severity.WARNING,CREATED_AT,EXPIRES_AT,
        "real-admin",true);
  }
}
