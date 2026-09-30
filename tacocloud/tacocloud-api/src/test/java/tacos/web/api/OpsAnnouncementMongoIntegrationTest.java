package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.OpsAnnouncement;
import tacos.OpsAnnouncement.Severity;
import tacos.data.OpsAnnouncementRepository;

@SpringBootTest(
    classes=OpsAnnouncementMongoIntegrationTest.TestApplication.class,
    webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={
      "spring.data.mongodb.database=tc33_announcements_test",
      "spring.data.mongodb.auto-index-creation=true"
    })
public class OpsAnnouncementMongoIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

  @Autowired private OpsAnnouncementRepository repository;
  @Autowired private AnnouncementService service;
  @Autowired private ReactiveMongoTemplate mongo;
  @Autowired private Clock clock;

  @Test
  public void shouldPersistAcrossServiceInstances() {
    AnnouncementService restarted = new AnnouncementService(repository,clock);

    StepVerifier.create(clean()
        .then(service.create(
            "Persistent",Severity.INFO,NOW.plusSeconds(3600),"admin"))
        .thenMany(restarted.activeAnnouncements()).collectList())
        .assertNext(items -> {
          assertEquals(1,items.size());
          assertEquals("Persistent",items.get(0).getText());
          assertEquals("admin",items.get(0).getCreatedBy());
        })
        .verifyComplete();
  }

  @Test
  public void shouldKeepConcurrentWritesIndependent() {
    StepVerifier.create(clean()
        .then(Mono.zip(
            service.create("A",Severity.INFO,NOW.plusSeconds(3600),"admin"),
            service.create("B",Severity.WARNING,NOW.plusSeconds(3600),"admin")))
        .flatMap(created -> repository.findAll().collectList()
            .map(saved -> new ConcurrentResult(
                created.getT1(),created.getT2(),saved))))
        .assertNext(result -> {
          assertNotEquals(result.first.getId(),result.second.getId());
          assertEquals(2,result.saved.size());
          assertTrue(result.saved.stream().anyMatch(item -> "A".equals(item.getText())));
          assertTrue(result.saved.stream().anyMatch(item -> "B".equals(item.getText())));
        })
        .verifyComplete();
  }

  @Test
  public void shouldDeleteOnlyRequestedStableId() {
    StepVerifier.create(clean()
        .then(Mono.zip(
            service.create("A",Severity.INFO,NOW.plusSeconds(3600),"admin"),
            service.create("B",Severity.INFO,NOW.plusSeconds(3600),"admin")))
        .flatMap(created -> service.delete(created.getT1().getId())
            .then(Mono.zip(
                repository.existsById(created.getT1().getId()),
                repository.existsById(created.getT2().getId())))))
        .assertNext(exists -> {
          assertFalse(exists.getT1());
          assertTrue(exists.getT2());
        })
        .verifyComplete();
  }

  @Test
  public void shouldHideExpiredAndInactiveAnnouncementsUsingFixedClock() {
    OpsAnnouncement expired = new OpsAnnouncement(
        null,"Expired",Severity.WARNING,NOW.minusSeconds(7200),
        NOW.minusSeconds(1),"admin",true);
    OpsAnnouncement inactive = new OpsAnnouncement(
        null,"Inactive",Severity.INFO,NOW.minusSeconds(60),
        NOW.plusSeconds(3600),"admin",false);

    StepVerifier.create(clean()
        .then(service.create(
            "Visible",Severity.CRITICAL,NOW.plusSeconds(3600),"admin"))
        .thenMany(Flux.just(expired,inactive).concatMap(repository::save))
        .thenMany(service.activeAnnouncements()).collectList())
        .assertNext(items -> {
          assertEquals(1,items.size());
          assertEquals("Visible",items.get(0).getText());
        })
        .verifyComplete();
  }

  @Test
  public void shouldEnforceActiveAnnouncementLimit() {
    StepVerifier.create(clean()
        .thenMany(Flux.range(1,AnnouncementService.MAX_ACTIVE_ANNOUNCEMENTS)
            .concatMap(number -> service.create(
                "Announcement " + number,Severity.INFO,
                NOW.plusSeconds(3600),"admin")))
        .then(service.create(
            "One too many",Severity.INFO,NOW.plusSeconds(3600),"admin")))
        .expectErrorSatisfies(error -> assertEquals(
            "ANNOUNCEMENT_LIMIT_REACHED",
            ((tacos.web.api.error.ApiExceptionHandler.ApiException) error)
                .getCode()))
        .verify();
  }

  @Test
  public void shouldCreateExpiryTtlIndex() {
    StepVerifier.create(mongo.indexOps(OpsAnnouncement.class).getIndexInfo()
        .map(index -> index.getName()).collectList())
        .assertNext(names -> assertTrue(
            names.contains("ops_announcement_expiry_ttl")))
        .verifyComplete();
  }

  private Mono<Void> clean() {
    return mongo.remove(new Query(),OpsAnnouncement.class).then();
  }

  private static class ConcurrentResult {
    private final OpsAnnouncement first;
    private final OpsAnnouncement second;
    private final java.util.List<OpsAnnouncement> saved;

    private ConcurrentResult(
        OpsAnnouncement first,OpsAnnouncement second,
        java.util.List<OpsAnnouncement> saved) {
      this.first = first;
      this.second = second;
      this.saved = saved;
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableReactiveMongoRepositories(
      basePackageClasses=OpsAnnouncementRepository.class)
  @Import(AnnouncementService.class)
  static class TestApplication {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW,ZoneOffset.UTC);
    }
  }
}
