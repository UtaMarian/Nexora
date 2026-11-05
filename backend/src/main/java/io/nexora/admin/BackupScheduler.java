package io.nexora.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs farm backups on a schedule, in UTC, and keeps the latest few.
 *
 * <p>The next run is stored in the database and claimed with a compare-and-set, so servers sharing a database start
 * one backup between them. A run missed while everything was down happens at the next start, once.
 */
@Service
public class BackupScheduler {
    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);
    private static final String SETTING = "backup.schedule";
    private static final String NEXT_RUN = "backup.schedule.next";

    public enum Frequency { DAILY, WEEKLY }

    /** @param runAs the administrator who saved the schedule, named on its backups */
    record Stored(boolean enabled, Frequency frequency, int hour, DayOfWeek day, int keep, UUID runAs) {}

    public record ScheduleRequest(boolean enabled, Frequency frequency, Integer hour, DayOfWeek day, Integer keep) {}

    public record LastRun(UUID jobId, AdminJob.Status status, String message, Instant finishedAt) {}

    public record Schedule(boolean enabled, Frequency frequency, int hour, DayOfWeek day, int keep, Instant nextRun, LastRun lastRun) {}

    private final FarmSettingRepository settings;
    private final BackupService backups;
    private final AdminJobRepository jobs;
    private final PrincipalRepository principals;
    private final AuditService audit;
    private final ObjectMapper json;

    public BackupScheduler(FarmSettingRepository settings, BackupService backups, AdminJobRepository jobs,
                           PrincipalRepository principals, AuditService audit, ObjectMapper json) {
        this.settings = settings;
        this.backups = backups;
        this.jobs = jobs;
        this.principals = principals;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Schedule schedule() {
        Stored s = stored();
        Instant next = s.enabled() ? settings.findById(NEXT_RUN).map(v -> Instant.parse(v.getValue())).orElse(null) : null;
        LastRun last = jobs.findFirstByTypeOrderByCreatedAtDesc(BackupService.SCHEDULED_JOB)
                .map(j -> new LastRun(j.getId(), j.getStatus(), j.getMessage(), j.getFinishedAt())).orElse(null);
        return new Schedule(s.enabled(), s.frequency(), s.hour(), s.day(), s.keep(), next, last);
    }

    @Transactional
    public Schedule save(CurrentUser actor, ScheduleRequest r) {
        int hour = r.hour() == null ? 2 : r.hour();
        int keep = r.keep() == null ? 7 : r.keep();
        if (hour < 0 || hour > 23) throw ApiException.badRequest("invalid_schedule", "Choose an hour from 0 to 23");
        if (keep < 1 || keep > 365) throw ApiException.badRequest("invalid_schedule", "Keep between 1 and 365 backups");
        Stored s = new Stored(r.enabled(), r.frequency() == null ? Frequency.DAILY : r.frequency(), hour,
                r.day() == null ? DayOfWeek.SUNDAY : r.day(), keep, actor.id());
        try {
            settings.save(new FarmSetting(SETTING, json.writeValueAsString(s), actor.id()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        settings.save(new FarmSetting(NEXT_RUN, nextAfter(s, Instant.now()).toString(), actor.id()));
        audit.record(actor, "BACKUP_SCHEDULE", null, Map.of("enabled", s.enabled(), "frequency", s.frequency().name(), "keep", keep));
        return new Schedule(s.enabled(), s.frequency(), s.hour(), s.day(), s.keep(),
                s.enabled() ? nextAfter(s, Instant.now()) : null, null);
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    void tick() {
        try {
            runDue(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Could not start the scheduled backup: {}", e.toString()); // the next tick tries again
        }
    }

    /** Starts the backup if one is due and this server wins it. Returns the job, or null. */
    public AdminJob runDue(Instant now) {
        Stored s = stored();
        if (!s.enabled()) return null;
        String next = settings.findById(NEXT_RUN).map(FarmSetting::getValue).orElse(null);
        if (next == null || Instant.parse(next).isAfter(now)) return null;
        if (settings.compareAndSet(NEXT_RUN, next, nextAfter(s, now).toString()) == 0) return null; // another server took it
        if (!jobs.findByTypeAndStatusIn(BackupService.SCHEDULED_JOB, List.of(AdminJob.Status.QUEUED, AdminJob.Status.RUNNING)).isEmpty()) {
            log.warn("Skipped a scheduled backup because the previous one is still running");
            return null;
        }
        Principal runAs = s.runAs() == null ? null : principals.findById(s.runAs()).orElse(null);
        if (runAs == null) {
            log.error("Skipped a scheduled backup: the administrator who saved the schedule no longer exists. Save it again");
            return null;
        }
        // a person leaving should not stop backups, so a disabled account still names them: it grants nothing here
        return backups.backupFarmOnSchedule(CurrentUser.system(runAs), s.keep());
    }

    /** The first run strictly after {@code now}. */
    static Instant nextAfter(Stored s, Instant now) {
        ZonedDateTime at = now.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).withHour(s.hour());
        if (s.frequency() == Frequency.WEEKLY) at = at.with(TemporalAdjusters.nextOrSame(s.day()));
        if (!at.toInstant().isAfter(now)) at = s.frequency() == Frequency.WEEKLY ? at.plusWeeks(1) : at.plusDays(1);
        return at.toInstant();
    }

    private Stored stored() {
        return settings.findById(SETTING).map(v -> {
            try {
                return json.readValue(v.getValue(), Stored.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("The backup schedule is unreadable", e);
            }
        }).orElse(new Stored(false, Frequency.DAILY, 2, DayOfWeek.SUNDAY, 7, null));
    }
}
