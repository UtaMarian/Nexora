package io.nexora.admin;

import io.nexora.api.Dtos.PrincipalRef;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.content.Node;
import io.nexora.content.QuotaService;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.IdentityService;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.portal.SiteTemplate;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Central Administration for farm administrators: overview and health, site collections, backup and restore,
 * import/export, background jobs, server logs, users and settings. {@code /api/v1/admin/**} requires the
 * administrator role (SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final FarmService farm;
    private final BackupService backups;
    private final JobService jobs;
    private final LogBuffer logs;
    private final IdentityService identity;
    private final PrincipalRepository principals;
    private final AuditService audit;
    private final BackupScheduler schedule;
    private final QuotaService quotas;

    public AdminController(FarmService farm, BackupService backups, JobService jobs, LogBuffer logs,
                           IdentityService identity, PrincipalRepository principals, AuditService audit,
                           BackupScheduler schedule, QuotaService quotas) {
        this.schedule = schedule;
        this.quotas = quotas;
        this.farm = farm;
        this.backups = backups;
        this.jobs = jobs;
        this.logs = logs;
        this.identity = identity;
        this.principals = principals;
        this.audit = audit;
    }

    public record JobDto(UUID id, String type, String title, AdminJob.Status status, int progress, String message,
                         Map<String, Object> params, Map<String, Object> result, PrincipalRef createdBy,
                         Instant createdAt, Instant startedAt, Instant finishedAt, String log) {}

    public record CreateSite(@NotBlank String url, String title, String description, SiteTemplate template, String owner) {}

    public record RestoreRequest(List<BackupService.SiteRestore> sites, boolean directory) {}

    public record UserRow(UUID id, String login, String displayName, String email, boolean admin, boolean enabled,
                          boolean directory, Instant createdAt) {}

    public record UserPatch(Boolean enabled, Boolean admin) {}

    public record QuotaRequest(@jakarta.validation.constraints.NotNull Long maxBytes, Long warnBytes) {}

    // ---- overview ----

    @GetMapping("/overview")
    public FarmService.Overview overview() {
        return farm.overview();
    }

    @GetMapping("/health")
    public List<FarmService.Health> health() {
        return farm.health();
    }

    @GetMapping("/settings")
    public Map<String, String> settings() {
        return farm.settings();
    }

    // ---- sites ----

    @GetMapping("/sites")
    public List<FarmService.SiteRow> sites() {
        return farm.sites();
    }

    @PostMapping("/sites")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSite(@RequestBody CreateSite body) {
        Node site = farm.createSite(CurrentUser.get(), body.url(), body.title(), body.description(), body.template(), body.owner());
        return Map.of("id", site.getId(), "path", site.getPath());
    }

    @DeleteMapping("/sites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSite(@PathVariable UUID id) {
        farm.deleteSite(CurrentUser.get(), id);
    }

    @PutMapping("/sites/{id}/quota")
    public QuotaService.Usage setQuota(@PathVariable UUID id, @jakarta.validation.Valid @RequestBody QuotaRequest body) {
        return quotas.set(CurrentUser.get(), id, body.maxBytes(), body.warnBytes());
    }

    @DeleteMapping("/sites/{id}/quota")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeQuota(@PathVariable UUID id) {
        quotas.remove(CurrentUser.get(), id);
    }

    @PostMapping("/sites/{id}/backup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDto backupSite(@PathVariable UUID id) {
        return dto(backups.backupSite(CurrentUser.get(), id), false);
    }

    // ---- backup and restore ----

    @GetMapping("/backups")
    public List<BackupService.BackupInfo> backups() throws IOException {
        return backups.list();
    }

    @GetMapping("/backups/schedule")
    public BackupScheduler.Schedule backupSchedule() {
        return schedule.schedule();
    }

    @PutMapping("/backups/schedule")
    public BackupScheduler.Schedule saveBackupSchedule(@RequestBody BackupScheduler.ScheduleRequest body) {
        return schedule.save(CurrentUser.get(), body);
    }

    @PostMapping("/backups/farm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDto backupFarm() {
        return dto(backups.backupFarm(CurrentUser.get()), false);
    }

    @GetMapping("/backups/{id}")
    public BackupService.BackupInfo backup(@PathVariable String id) throws IOException {
        return backups.get(id);
    }

    @DeleteMapping("/backups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBackup(@PathVariable String id) throws IOException {
        backups.delete(CurrentUser.get(), id);
    }

    /** Downloads one site package (export): it can be imported into this or another farm. */
    @GetMapping("/backups/{id}/sites/{site}/package")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id, @PathVariable String site) throws IOException {
        Path file = backups.sitePackage(id, site);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(site + "-" + id + ".nexora.zip").build().toString())
                .body(new FileSystemResource(file));
    }

    @PostMapping("/backups/{id}/restore")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDto restore(@PathVariable String id, @RequestBody RestoreRequest body) throws IOException {
        return dto(backups.restore(CurrentUser.get(), id, body.sites(), body.directory()), false);
    }

    /** Uploads a site package exported from this or another farm; restore it from the backup list. */
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BackupService.BackupInfo upload(@RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return backups.upload(CurrentUser.get(), in, file.getOriginalFilename());
        }
    }

    // ---- jobs and logs ----

    @GetMapping("/jobs")
    public List<JobDto> jobs(@RequestParam(defaultValue = "50") int limit) {
        List<AdminJob> list = jobs.latest(limit);
        Map<UUID, Principal> people = identity.byIds(list.stream().map(AdminJob::getCreatedBy).toList());
        return list.stream().map(j -> dto(j, people, false)).toList();
    }

    @GetMapping("/jobs/{id}")
    public JobDto job(@PathVariable UUID id) {
        return dto(jobs.get(id), true);
    }

    @PostMapping("/jobs/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id) {
        jobs.cancel(id);
    }

    @GetMapping("/logs")
    public List<LogBuffer.Entry> logs(@RequestParam(defaultValue = "INFO") String level,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "300") int limit) {
        return logs.query(level, q, Math.max(1, Math.min(limit, LogBuffer.CAPACITY)));
    }

    // ---- users ----

    @GetMapping("/users")
    @Transactional(readOnly = true)
    public List<UserRow> users(@RequestParam(required = false) String q) {
        List<Principal> list = q == null || q.isBlank() ? principals.findAll() : principals.search(q.strip());
        return list.stream().filter(p -> !p.isGroup())
                .sorted(Comparator.comparing(p -> p.getDisplayName().toLowerCase(Locale.ROOT)))
                .map(p -> new UserRow(p.getId(), p.getLogin(), p.getDisplayName(), p.getEmail(), p.isAdmin(), p.isEnabled(),
                        p.getPasswordHash() == null, p.getCreatedAt()))
                .toList();
    }

    @PatchMapping("/users/{id}")
    @Transactional
    public UserRow updateUser(@PathVariable UUID id, @RequestBody UserPatch body) {
        CurrentUser actor = CurrentUser.get();
        Principal p = principals.findById(id).filter(x -> !x.isGroup()).orElseThrow(() -> ApiException.notFound("User"));
        if (p.getId().equals(actor.id()) && (Boolean.FALSE.equals(body.admin()) || Boolean.FALSE.equals(body.enabled()))) {
            throw ApiException.conflict("self_lockout", "You cannot disable your own account or remove your own administrator role");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("user", p.getLogin());
        if (body.enabled() != null) { p.setEnabled(body.enabled()); details.put("enabled", body.enabled()); }
        if (body.admin() != null) { p.setAdmin(body.admin()); details.put("admin", body.admin()); }
        principals.save(p);
        audit.record(actor, "USER_UPDATE", null, details);
        return new UserRow(p.getId(), p.getLogin(), p.getDisplayName(), p.getEmail(), p.isAdmin(), p.isEnabled(),
                p.getPasswordHash() == null, p.getCreatedAt());
    }

    private JobDto dto(AdminJob j, boolean withLog) {
        return dto(j, identity.byIds(List.of(j.getCreatedBy())), withLog);
    }

    private static JobDto dto(AdminJob j, Map<UUID, Principal> people, boolean withLog) {
        return new JobDto(j.getId(), j.getType(), j.getTitle(), j.getStatus(), j.getProgress(), j.getMessage(), j.getParams(),
                j.getResult(), PrincipalRef.of(people.get(j.getCreatedBy())), j.getCreatedAt(), j.getStartedAt(),
                j.getFinishedAt(), withLog ? j.getLog() : null);
    }
}
