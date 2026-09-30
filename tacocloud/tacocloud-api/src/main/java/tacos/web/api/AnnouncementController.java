package tacos.web.api;

import java.time.Instant;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.OpsAnnouncement;
import tacos.OpsAnnouncement.Severity;

@RestController
@RequestMapping("/api/admin/announcements")
public class AnnouncementController {

  private final AnnouncementService announcements;

  public AnnouncementController(AnnouncementService announcements) {
    this.announcements = announcements;
  }

  @GetMapping
  public Flux<AnnouncementResponse> activeAnnouncements() {
    return announcements.activeAnnouncements().map(AnnouncementResponse::from);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<AnnouncementResponse> create(
      @Valid @RequestBody AnnouncementRequest request,
      Authentication authentication) {
    return announcements.create(
        request.getText(),request.getSeverity(),request.getExpiresAt(),
        authentication.getName())
        .map(AnnouncementResponse::from);
  }

  @DeleteMapping("/{announcementId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> delete(@PathVariable String announcementId) {
    return announcements.delete(announcementId);
  }

  @Data
  @NoArgsConstructor
  public static class AnnouncementRequest {

    @NotBlank(message="text is required")
    @Size(max=AnnouncementService.MAX_TEXT_LENGTH,
        message="text must not exceed 500 characters")
    private String text;

    @NotNull(message="severity is required")
    private Severity severity;

    @NotNull(message="expiresAt is required")
    private Instant expiresAt;
  }

  @Data
  @AllArgsConstructor
  public static class AnnouncementResponse {

    private String id;
    private String text;
    private Severity severity;
    private Instant createdAt;
    private Instant expiresAt;
    private boolean active;

    private static AnnouncementResponse from(OpsAnnouncement announcement) {
      return new AnnouncementResponse(
          announcement.getId(),announcement.getText(),
          announcement.getSeverity(),announcement.getCreatedAt(),
          announcement.getExpiresAt(),announcement.isActive());
    }
  }
}
