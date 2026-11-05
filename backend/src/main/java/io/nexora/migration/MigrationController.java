package io.nexora.migration;

import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.admin.AdminJob;
import io.nexora.common.ApiException;
import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SharePoint migration. {@code /api/v1/admin/**} is for farm administrators. The redirect lookup is open to
 * every signed-in user and only answers for content that user may see.
 */
@RestController
@RequestMapping("/api/v1")
public class MigrationController {
    private final MigrationService migration;
    private final RedirectService redirects;
    private final PermissionService permissions;

    public MigrationController(MigrationService migration, RedirectService redirects, PermissionService permissions) {
        this.migration = migration;
        this.redirects = redirects;
        this.permissions = permissions;
    }

    /** A source farm. The stored secret is never sent back, only whether there is one. */
    public record SourceDto(UUID id, String name, String baseUrl, SharePointClient.Auth auth, String username,
                            String domain, boolean hasSecret, Instant createdAt) {}

    public record TaskDto(UUID id, UUID sourceId, String sourceWeb, String targetSite, Map<String, Object> options,
                          Map<String, Object> scan, MigrationTask.Status status, UUID lastJobId, Instant lastSyncAt,
                          long items, Instant createdAt) {}

    public record ScanRequest(boolean deep) {}

    public record JobRef(UUID id, String type, String title, AdminJob.Status status) {}

    public record Redirect(String path) {}

    // ---- sources ----

    @GetMapping("/admin/migration/sources")
    public List<SourceDto> sources() {
        return migration.sources().stream().map(MigrationController::dto).toList();
    }

    @PostMapping("/admin/migration/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public SourceDto createSource(@RequestBody MigrationService.SourceRequest body) {
        return dto(migration.createSource(CurrentUser.get(), body));
    }

    @PutMapping("/admin/migration/sources/{id}")
    public SourceDto updateSource(@PathVariable UUID id, @RequestBody MigrationService.SourceRequest body) {
        return dto(migration.updateSource(id, body));
    }

    @DeleteMapping("/admin/migration/sources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSource(@PathVariable UUID id) {
        migration.deleteSource(id);
    }

    @PostMapping("/admin/migration/sources/{id}/test")
    public Map<String, Object> test(@PathVariable UUID id) {
        return migration.test(id);
    }

    @PostMapping("/admin/migration/sources/{id}/discover")
    public List<Map<String, String>> discover(@PathVariable UUID id) {
        return migration.discover(id);
    }

    // ---- tasks ----

    @GetMapping("/admin/migration/tasks")
    public List<TaskDto> tasks() {
        return migration.tasks().stream().map(this::dto).toList();
    }

    @PostMapping("/admin/migration/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDto createTask(@RequestBody MigrationService.TaskRequest body) {
        return dto(migration.createTask(CurrentUser.get(), body));
    }

    @GetMapping("/admin/migration/tasks/{id}")
    public TaskDto task(@PathVariable UUID id) {
        return dto(migration.task(id));
    }

    @PutMapping("/admin/migration/tasks/{id}/options")
    public TaskDto options(@PathVariable UUID id, @RequestBody Map<String, Object> options) {
        return dto(migration.updateOptions(id, options));
    }

    @DeleteMapping("/admin/migration/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTask(@PathVariable UUID id) {
        migration.deleteTask(id);
    }

    @PostMapping("/admin/migration/tasks/{id}/scan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobRef scan(@PathVariable UUID id, @RequestBody(required = false) ScanRequest body) {
        return ref(migration.scan(CurrentUser.get(), id, body != null && body.deep()));
    }

    @PostMapping("/admin/migration/tasks/{id}/copy")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobRef copy(@PathVariable UUID id) {
        return ref(migration.copy(CurrentUser.get(), id));
    }

    // ---- redirects ----

    /** Where an old SharePoint link points now; 404 when unknown or not visible to the caller. */
    @GetMapping("/redirects/resolve")
    public Redirect resolve(@RequestParam String url) {
        CurrentUser user = CurrentUser.get();
        Node node = redirects.resolve(url, n -> permissions.has(user, n, Right.VIEW))
                .orElseThrow(() -> ApiException.notFound("Link"));
        return new Redirect(node.getPath());
    }

    private static SourceDto dto(MigrationSource s) {
        return new SourceDto(s.getId(), s.getName(), s.getBaseUrl(), s.getAuth(), s.getUsername(), s.getDomain(),
                s.getSecret() != null, s.getCreatedAt());
    }

    private TaskDto dto(MigrationTask t) {
        return new TaskDto(t.getId(), t.getSourceId(), t.getSourceWeb(), t.getTargetSite(), t.getOptions(), t.getScan(),
                t.getStatus(), t.getLastJobId(), t.getLastSyncAt(), migration.itemCount(t.getId()), t.getCreatedAt());
    }

    private static JobRef ref(AdminJob j) {
        return new JobRef(j.getId(), j.getType(), j.getTitle(), j.getStatus());
    }
}
