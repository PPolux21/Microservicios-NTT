package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.OpsAnnouncement;
import tacos.OpsAnnouncement.Severity;
import tacos.data.OpsAnnouncementRepository;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

public class AnnouncementServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

  private OpsAnnouncementRepository repository;
  private AnnouncementService service;

  @BeforeEach
  public void setup() {
    repository = Mockito.mock(OpsAnnouncementRepository.class);
    service = new AnnouncementService(
        repository,Clock.fixed(NOW,ZoneOffset.UTC));
  }

  @Test
  public void shouldRejectInvalidTextSeverityAndExpiration() {
    for (String text : Arrays.asList(
        "", "   ", "line\nbreak", "tab\tvalue",
        "x".repeat(AnnouncementService.MAX_TEXT_LENGTH + 1))) {
      assertBadRequest(service.create(
          text,Severity.INFO,NOW.plusSeconds(60),"admin"));
    }

    assertBadRequest(service.create(
        "Maintenance",null,NOW.plusSeconds(60),"admin"));
    assertBadRequest(service.create(
        "Maintenance",Severity.WARNING,NOW,"admin"));
    assertBadRequest(service.create(
        "Maintenance",Severity.WARNING,NOW.minusSeconds(1),"admin"));

    verify(repository,never())
        .countByActiveTrueAndExpiresAtAfter(any(Instant.class));
  }

  @Test
  public void shouldAcceptExactlyFiveHundredCharacters() {
    when(repository.countByActiveTrueAndExpiresAtAfter(NOW))
        .thenReturn(Mono.just(0L));
    when(repository.save(any(OpsAnnouncement.class)))
        .thenAnswer(invocation -> {
          OpsAnnouncement saved = invocation.getArgument(0);
          saved.setId("A-500");
          return Mono.just(saved);
        });

    String text = "x".repeat(AnnouncementService.MAX_TEXT_LENGTH);
    StepVerifier.create(service.create(
        text,Severity.INFO,NOW.plusSeconds(60),"admin"))
        .assertNext(saved -> {
          assertEquals("A-500",saved.getId());
          assertEquals(AnnouncementService.MAX_TEXT_LENGTH,
              saved.getText().length());
          assertEquals(NOW,saved.getCreatedAt());
          assertEquals("admin",saved.getCreatedBy());
          assertTrue(saved.isActive());
        })
        .verifyComplete();
  }

  @Test
  public void shouldRejectCreationWhenActiveLimitIsReached() {
    when(repository.countByActiveTrueAndExpiresAtAfter(NOW))
        .thenReturn(Mono.just(
            (long) AnnouncementService.MAX_ACTIVE_ANNOUNCEMENTS));

    StepVerifier.create(service.create(
        "Maintenance",Severity.WARNING,NOW.plusSeconds(60),"admin"))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof ApiException);
          assertEquals("ANNOUNCEMENT_LIMIT_REACHED",
              ((ApiException) error).getCode());
          assertEquals(409,((ApiException) error).getStatus().value());
        })
        .verify();

    verify(repository,never()).save(any(OpsAnnouncement.class));
  }

  @Test
  public void shouldReturnNotFoundForUnknownStableId() {
    when(repository.findById("missing")).thenReturn(Mono.empty());

    StepVerifier.create(service.delete("missing"))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof ApiException);
          assertEquals("ANNOUNCEMENT_NOT_FOUND",
              ((ApiException) error).getCode());
          assertEquals(404,((ApiException) error).getStatus().value());
        })
        .verify();
  }

  private void assertBadRequest(Mono<OpsAnnouncement> result) {
    StepVerifier.create(result)
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof ApiException);
          assertEquals(400,((ApiException) error).getStatus().value());
        })
        .verify();
  }
}
