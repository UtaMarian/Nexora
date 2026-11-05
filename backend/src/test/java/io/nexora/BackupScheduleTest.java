package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.admin.AdminJob;
import io.nexora.admin.BackupScheduler;
import io.nexora.admin.BackupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Farm backups on a schedule: when they run, that only one runs, and which old ones go. */
@TestPropertySource(properties = "nexora.admin.backup-dir=${java.io.tmpdir}/nexora-test-scheduled-backups")
class BackupScheduleTest extends ApiTestSupport {
    private static final Instant LATER = Instant.now().plus(800, ChronoUnit.DAYS);

    @Autowired BackupScheduler scheduler;
    @Autowired BackupService backups;

    @BeforeEach
    void emptyBackupFolder() throws Exception {
        if (!Files.isDirectory(backups.root())) return;
        try (Stream<Path> walk = Files.walk(backups.root())) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(backups.root())) Files.deleteIfExists(p);
            }
        }
    }

    @AfterEach
    void turnOff() throws Exception {
        save(false, 7, 200);
    }

    private JsonNode save(boolean enabled, int keep, int status) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("enabled", enabled, "frequency", "DAILY", "hour", 3, "keep", keep));
        return call(ADMIN, jsonBody(put("/api/v1/admin/backups/schedule"), body), status);
    }

    private JsonNode finish(AdminJob job) throws Exception {
        assertThat(job).isNotNull();
        for (int i = 0; i < 600; i++) {
            JsonNode j = call(ADMIN, get("/api/v1/admin/jobs/{id}", job.getId()), 200);
            if (!List.of("QUEUED", "RUNNING").contains(j.get("status").asText())) return j;
            Thread.sleep(100);
        }
        throw new AssertionError("The job did not finish");
    }

    /** Runs the schedule as if its time had come. The next run moves past LATER, so saving again rearms it. */
    private JsonNode runNow() throws Exception {
        JsonNode job = finish(scheduler.runDue(LATER));
        assertThat(job.get("status").asText()).isEqualTo("SUCCEEDED");
        return job;
    }

    @Test
    void onlyFarmAdministratorsTouchTheSchedule() throws Exception {
        String user = createUser("plain");
        call(user, get("/api/v1/admin/backups/schedule"), 403);
        call(user, jsonBody(put("/api/v1/admin/backups/schedule"), Map.of("enabled", true)), 403);
    }

    @Test
    void itIsOffUntilSomeoneTurnsItOn() throws Exception {
        save(false, 7, 200);
        assertThat(call(ADMIN, get("/api/v1/admin/backups/schedule"), 200).get("nextRun")).isNull();
        assertThat(scheduler.runDue(LATER)).isNull();
    }

    @Test
    void savingShowsTheNextRunAndRefusesNonsense() throws Exception {
        JsonNode saved = save(true, 5, 200);
        assertThat(Instant.parse(saved.get("nextRun").asText())).isAfter(Instant.now());
        assertThat(saved.get("nextRun").asText()).contains("T03:00:00");
        assertThat(saved.get("keep").asInt()).isEqualTo(5);
        save(true, 0, 400);
        save(true, 366, 400);
        call(ADMIN, jsonBody(put("/api/v1/admin/backups/schedule"), Map.of("enabled", true, "hour", 24)), 400);
    }

    @Test
    void nothingRunsBeforeItsTime() throws Exception {
        save(true, 5, 200);
        assertThat(scheduler.runDue(Instant.now())).isNull();
    }

    @Test
    void aDueRunMakesAScheduledBackupAndIsClaimedOnce() throws Exception {
        save(true, 5, 200);
        AdminJob won = scheduler.runDue(LATER);
        assertThat(scheduler.runDue(LATER)).as("a second server asking for the same run gets nothing").isNull();
        assertThat(finish(won).get("status").asText()).isEqualTo("SUCCEEDED");

        assertThat(backups.list()).hasSize(1);
        BackupService.BackupInfo made = backups.list().get(0);
        assertThat(made.scheduled()).isTrue();
        assertThat(made.partial()).isFalse();
        assertThat(made.includesDirectory()).isTrue();

        JsonNode schedule = call(ADMIN, get("/api/v1/admin/backups/schedule"), 200);
        assertThat(schedule.get("lastRun").get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(Instant.parse(schedule.get("nextRun").asText())).isAfter(LATER);
    }

    @Test
    void onlyTheNewestScheduledBackupsStayAndManualOnesAreLeftAlone() throws Exception {
        JsonNode manual = call(ADMIN, post("/api/v1/admin/backups/farm"), 202);
        for (int i = 0; i < 600 && !backups.list().stream().anyMatch(b -> !b.partial()); i++) Thread.sleep(100);
        String kept = backups.list().get(0).id();

        save(true, 1, 200);
        runNow();
        String first = backups.list().stream().filter(BackupService.BackupInfo::scheduled).findFirst().orElseThrow().id();
        Thread.sleep(1100); // backup ids carry the time to the second
        save(true, 1, 200);
        runNow();

        List<BackupService.BackupInfo> left = backups.list();
        assertThat(left).extracting(BackupService.BackupInfo::id).contains(kept).doesNotContain(first).hasSize(2);
        assertThat(manual).isNotNull();
    }

    @Test
    void whatAFailedRunLeftIsCleanedUpByTheNextGoodOne() throws Exception {
        Path broken = Files.createDirectories(backups.root().resolve("20200101-000000-farm"));
        Files.writeString(broken.resolve("backup.json"), """
                {"id":"20200101-000000-farm","kind":"FARM","label":"Farm backup (in progress)",
                 "createdAt":"2020-01-01T00:00:00Z","createdBy":"admin","sites":[],"includesDirectory":true,
                 "sizeBytes":0,"scheduled":true,"partial":true}""");
        save(true, 5, 200);
        runNow();
        assertThat(broken).doesNotExist();
    }

    @Test
    void backupsMadeBeforeSchedulesExistedStillLoadAndAreNeverPruned() throws Exception {
        Path old = Files.createDirectories(backups.root().resolve("20200101-000000-site-old"));
        Files.writeString(old.resolve("backup.json"), """
                {"id":"20200101-000000-site-old","kind":"SITE","label":"Site backup",
                 "createdAt":"2020-01-01T00:00:00Z","createdBy":"admin","sites":[],"includesDirectory":false,"sizeBytes":0}""");
        save(true, 1, 200);
        runNow();
        assertThat(backups.list()).extracting(BackupService.BackupInfo::id).contains("20200101-000000-site-old");
    }

    @Test
    void healthReportsTheSchedule() throws Exception {
        save(true, 5, 200);
        JsonNode health = call(ADMIN, get("/api/v1/admin/health"), 200);
        assertThat(health).filteredOn(h -> h.get("component").asText().equals("Scheduled backup")).hasSize(1)
                .allSatisfy(h -> assertThat(h.get("status").asText()).isIn("OK", "WARNING"));
    }
}
