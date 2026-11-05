package io.nexora.migration;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.admin.AdminJob;
import io.nexora.admin.JobService;
import io.nexora.common.ApiException;
import io.nexora.content.Names;
import io.nexora.identity.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Source farms and migration tasks; scans and copies run as background jobs. */
@Service
public class MigrationService {
    public record SourceRequest(String name, String baseUrl, SharePointClient.Auth auth, String username, String domain,
                                String secret) {}

    public record TaskRequest(UUID sourceId, String sourceWeb, String targetSite, Map<String, Object> options) {}

    private final MigrationSourceRepository sources;
    private final MigrationTaskRepository tasks;
    private final MigrationItemRepository items;
    private final MigrationEngine engine;
    private final JobService jobs;
    private final Secrets secrets;
    private final TransactionTemplate tx;

    public MigrationService(MigrationSourceRepository sources, MigrationTaskRepository tasks, MigrationItemRepository items,
                            MigrationEngine engine, JobService jobs, Secrets secrets, PlatformTransactionManager txManager) {
        this.sources = sources;
        this.tasks = tasks;
        this.items = items;
        this.engine = engine;
        this.jobs = jobs;
        this.secrets = secrets;
        this.tx = new TransactionTemplate(txManager);
    }

    // ---- sources -------------------------------------------------------------------------------

    public List<MigrationSource> sources() {
        return sources.findAllByOrderByNameAsc();
    }

    public MigrationSource createSource(CurrentUser actor, SourceRequest r) {
        validate(r);
        return sources.save(new MigrationSource(r.name().strip(), cleanUrl(r.baseUrl()), r.auth(), blank(r.username()), blank(r.domain()),
                secrets.encrypt(r.secret()), actor.id()));
    }

    /** Updates a source. An empty secret keeps the stored one. */
    public MigrationSource updateSource(UUID id, SourceRequest r) {
        validate(r);
        MigrationSource s = source(id);
        s.update(r.name().strip(), cleanUrl(r.baseUrl()), r.auth(), blank(r.username()), blank(r.domain()));
        if (r.secret() != null && !r.secret().isEmpty()) s.setSecret(secrets.encrypt(r.secret()));
        return sources.save(s);
    }

    public void deleteSource(UUID id) {
        MigrationSource s = source(id);
        if (!tasks.findBySourceId(id).isEmpty()) {
            throw ApiException.conflict("source_in_use", "Delete this farm's tasks first");
        }
        sources.delete(s);
    }

    /** Signs in and reads the root site, so a wrong address or password shows up before any scan. */
    public Map<String, Object> test(UUID id) {
        SharePointClient sp = client(source(id));
        try {
            JsonNode web = sp.json(sp.web("/", "/_api/web?$select=Title,ServerRelativeUrl,UIVersion"));
            return Map.of("ok", true, "title", web.path("Title").asText(""), "uiVersion", web.path("UIVersion").asInt());
        } catch (IOException e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /** Site collections the account can find, from SharePoint search. */
    public List<Map<String, String>> discover(UUID id) {
        SharePointClient sp = client(source(id));
        try {
            JsonNode res = sp.json(sp.web("/", "/_api/search/query?querytext=" + SharePointClient.literal("contentclass:STS_Site")
                    + "&selectproperties=" + SharePointClient.literal("Title,Path") + "&rowlimit=500&trimduplicates=false"));
            List<Map<String, String>> out = new ArrayList<>();
            for (JsonNode row : res.path("PrimaryQueryResult").path("RelevantResults").path("Table").path("Rows")) {
                String title = "", path = "";
                for (JsonNode cell : row.path("Cells")) {
                    if ("Title".equals(cell.path("Key").asText())) title = cell.path("Value").asText("");
                    if ("Path".equals(cell.path("Key").asText())) path = cell.path("Value").asText("");
                }
                if (path.isEmpty()) continue;
                out.add(Map.of("title", title, "url", path, "web", URI.create(path.replace(" ", "%20")).getPath()));
            }
            out.sort(Comparator.comparing(m -> m.get("web")));
            return out;
        } catch (IOException e) {
            throw ApiException.badRequest("discover_failed", "Could not list sites through SharePoint search: " + e.getMessage());
        }
    }

    // ---- tasks ---------------------------------------------------------------------------------

    public List<MigrationTask> tasks() {
        return tasks.findAllByOrderByCreatedAtDesc();
    }

    public MigrationTask task(UUID id) {
        return tasks.findById(id).orElseThrow(() -> ApiException.notFound("Migration task"));
    }

    public long itemCount(UUID taskId) {
        return items.countByTaskId(taskId);
    }

    public MigrationTask createTask(CurrentUser actor, TaskRequest r) {
        source(r.sourceId());
        if (r.sourceWeb() == null || r.sourceWeb().isBlank()) throw ApiException.badRequest("invalid_task", "Enter the site's address in SharePoint");
        MigrationEngine.Options.of(r.options());   // reject bad options now, not during the run
        return tasks.save(new MigrationTask(r.sourceId(), webPath(r.sourceWeb()), Names.siteSlug(r.targetSite()),
                r.options() == null ? Map.of() : r.options(), actor.id()));
    }

    public MigrationTask updateOptions(UUID id, Map<String, Object> options) {
        MigrationTask t = task(id);
        MigrationEngine.Options.of(options);
        t.setOptions(options == null ? Map.of() : options);
        return tasks.save(t);
    }

    /** Removes the task and its source-to-target map; copied content stays, old links stop redirecting. */
    public void deleteTask(UUID id) {
        assertIdle(task(id));
        tasks.deleteById(id);
    }

    public AdminJob scan(CurrentUser actor, UUID id, boolean deep) {
        MigrationTask t = task(id);
        assertIdle(t);
        SharePointClient sp = client(source(t.getSourceId()));
        AdminJob job = jobs.submit("MIGRATION_SCAN", "Scan " + sp.base() + t.getSourceWeb(), Map.of("taskId", id.toString(), "deep", deep), actor, ctx -> {
            Map<String, Object> report = engine.scan(sp, t.getSourceWeb(), deep, ctx);
            change(id, x -> {
                x.setScan(report);
                if (x.getStatus() == MigrationTask.Status.NEW || x.getStatus() == MigrationTask.Status.FAILED) x.setStatus(MigrationTask.Status.SCANNED);
            });
            return Map.of("warnings", ((List<?>) report.get("warnings")).size(), "issues", ((List<?>) report.get("issues")).size());
        });
        change(id, x -> x.setLastJobId(job.getId()));
        return job;
    }

    /** First run: a full copy. Later runs: only what changed since (delta sync). */
    public AdminJob copy(CurrentUser actor, UUID id) {
        MigrationTask t = task(id);
        assertIdle(t);
        SharePointClient sp = client(source(t.getSourceId()));
        boolean delta = t.getLastSyncAt() != null;
        MigrationEngine.Options options = MigrationEngine.Options.of(t.getOptions());
        String what = (delta ? "Delta sync " : "Copy ") + sp.base() + t.getSourceWeb() + " to /sites/" + t.getTargetSite();
        AdminJob job = jobs.submit(delta ? "MIGRATION_DELTA" : "MIGRATION_COPY", what, Map.of("taskId", id.toString()), actor, ctx -> {
            Instant started = Instant.now();
            try {
                MigrationEngine.Stats s = engine.copy(sp, t, options, actor, ctx);
                change(id, x -> {
                    x.setStatus(s.failed() > 0 ? MigrationTask.Status.FAILED : delta ? MigrationTask.Status.SYNCED : MigrationTask.Status.COPIED);
                    if (s.failed() == 0 || x.getLastSyncAt() != null) x.setLastSyncAt(started);
                });
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("created", s.created());
                result.put("updated", s.updated());
                result.put("unchanged", s.unchanged());
                result.put("versions", s.versions());
                result.put("bytes", s.bytes());
                result.put("deleted", s.deleted());
                result.put("failed", s.failed());
                result.put("warnings", s.warnings());
                return result;
            } catch (JobService.CancelledException e) {
                throw e;
            } catch (Exception e) {
                change(id, x -> x.setStatus(MigrationTask.Status.FAILED));
                throw e;
            }
        });
        change(id, x -> x.setLastJobId(job.getId()));
        return job;
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void assertIdle(MigrationTask t) {
        if (t.getLastJobId() != null) {
            try {
                if (!jobs.get(t.getLastJobId()).getStatus().isFinished()) {
                    throw ApiException.conflict("task_running", "This task is already running");
                }
            } catch (ApiException e) {
                if (e.getMessage() != null && e.getMessage().contains("already running")) throw e;
                // the job record is gone: nothing is running
            }
        }
    }

    private void change(UUID id, Consumer<MigrationTask> fn) {
        tx.executeWithoutResult(s -> tasks.findById(id).ifPresent(t -> {
            fn.accept(t);
            tasks.save(t);
        }));
    }

    private MigrationSource source(UUID id) {
        if (id == null) throw ApiException.badRequest("invalid_task", "Choose a SharePoint farm");
        return sources.findById(id).orElseThrow(() -> ApiException.notFound("Migration source"));
    }

    private SharePointClient client(MigrationSource s) {
        return new SharePointClient(s.getBaseUrl(), s.getAuth(), s.getUsername(), s.getDomain(), secrets.decrypt(s.getSecret()));
    }

    private static void validate(SourceRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("invalid_source", "Give the farm a name");
        if (r.auth() == null) throw ApiException.badRequest("invalid_source", "Choose an authentication method");
        cleanUrl(r.baseUrl());
    }

    private static String cleanUrl(String url) {
        String u = url == null ? "" : url.strip();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        try {
            URI uri = URI.create(u);
            if (uri.getHost() == null || !Set.of("http", "https").contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_source", "Enter the farm's address, for example https://sharepoint.contoso.com");
        }
        return u;
    }

    /** "https://sp/sites/hr/" or "sites/hr" to "/sites/hr". */
    static String webPath(String raw) {
        String w = raw.strip();
        if (w.matches("(?i)^https?://.*")) {
            int slash = w.indexOf('/', w.indexOf("://") + 3);
            w = slash < 0 ? "/" : w.substring(slash);
        }
        int q = w.indexOf('?');
        if (q >= 0) w = w.substring(0, q);
        if (!w.startsWith("/")) w = "/" + w;
        while (w.length() > 1 && w.endsWith("/")) w = w.substring(0, w.length() - 1);
        return w;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
