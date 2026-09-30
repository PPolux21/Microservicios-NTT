package tacos.web.api;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.OpsAnnouncement;
import tacos.OpsAnnouncement.Severity;
import tacos.data.OpsAnnouncementRepository;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

@Service
public class AnnouncementService {

  public static final int MAX_TEXT_LENGTH = 500;
  public static final int MAX_ACTIVE_ANNOUNCEMENTS = 20;

  private final OpsAnnouncementRepository announcements;
  private final Clock clock;

  public AnnouncementService(
      OpsAnnouncementRepository announcements,Clock clock) {
    this.announcements = announcements;
    this.clock = clock;
  }

  public Flux<OpsAnnouncement> activeAnnouncements() {
    return Flux.defer(() -> announcements
        .findByActiveTrueAndExpiresAtAfterOrderByCreatedAtDesc(clock.instant()));
  }

  public Mono<OpsAnnouncement> create(
      String text,Severity severity,Instant expiresAt,String createdBy) {
    return Mono.defer(() -> {
      Instant createdAt = clock.instant();
      validate(text,severity,createdAt,expiresAt,createdBy);

      return announcements.countByActiveTrueAndExpiresAtAfter(createdAt)
          .flatMap(active -> {
            if (active >= MAX_ACTIVE_ANNOUNCEMENTS) {
              return Mono.error(ApiException.conflict(
                  "ANNOUNCEMENT_LIMIT_REACHED",
                  "The maximum number of active announcements has been reached."));
            }
            OpsAnnouncement announcement = new OpsAnnouncement(
                null,text,severity,createdAt,expiresAt,createdBy,true);
            return announcements.save(announcement);
          });
    });
  }

  public Mono<Void> delete(String announcementId) {
    return announcements.findById(announcementId)
        .switchIfEmpty(Mono.error(ApiException.notFound(
            "ANNOUNCEMENT_NOT_FOUND",
            "The requested announcement does not exist.")))
        .flatMap(announcements::delete);
  }

  private void validate(
      String text,Severity severity,Instant createdAt,Instant expiresAt,
      String createdBy) {
    if (text == null || text.trim().isEmpty()
        || text.length() > MAX_TEXT_LENGTH
        || text.codePoints().anyMatch(Character::isISOControl)) {
      throw ApiException.badRequest(
          "ANNOUNCEMENT_TEXT_INVALID",
          "Announcement text must contain 1 to 500 characters without control characters.");
    }
    if (severity == null) {
      throw ApiException.badRequest(
          "ANNOUNCEMENT_SEVERITY_INVALID",
          "Announcement severity is required.");
    }
    if (expiresAt == null || !expiresAt.isAfter(createdAt)) {
      throw ApiException.badRequest(
          "ANNOUNCEMENT_EXPIRY_INVALID",
          "Announcement expiration must be later than its creation time.");
    }
    if (createdBy == null || createdBy.trim().isEmpty()) {
      throw ApiException.badRequest(
          "ANNOUNCEMENT_AUTHOR_INVALID",
          "An authenticated announcement author is required.");
    }
  }
}
