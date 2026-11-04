package io.nexora.admin;

import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.content.ContentService;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.GroupMember;
import io.nexora.identity.GroupMemberRepository;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.identity.sso.DirectorySyncService;
import io.nexora.office.WopiDiscovery;
import io.nexora.mail.MailService;
import io.nexora.portal.PortalService;
import io.nexora.portal.SiteTemplate;
import io.nexora.search.SearchAdminService;
import io.nexora.storage.BlobStore;
import io.nexora.storage.StorageProperties;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Central Administration: farm status, health checks and site collection management. */
@Service
public class FarmService {
    private final EntityManager em;
    private final DataSource dataSource;
    private final BlobStore store;
    private final StorageProperties storage;
    private final ObjectProvider<WopiDiscovery> office;
    private final ObjectProvider<DirectorySyncService> directory;
    private final NodeRepository nodes;
    private final PrincipalRepository principals;
    private final GroupMemberRepository members;
    private final ContentService content;
    private final PortalService portal;
    private final BackupService backups;
    private final JobService jobs;
    private final AuditService audit;
    private final SearchAdminService search;
    private final MailService mail;
    private final Environment env;

    public FarmService(EntityManager em, DataSource dataSource, BlobStore store, StorageProperties storage,
                       ObjectProvider<WopiDiscovery> office, ObjectProvider<DirectorySyncService> directory,
                       NodeRepository nodes, PrincipalRepository principals, GroupMemberRepository members,
                       ContentService content, PortalService portal, BackupService backups, JobService jobs,
                       AuditService audit, SearchAdminService search, MailService mail, Environment env) {
        this.em = em;
        this.dataSource = dataSource;
        this.store = store;
        this.storage = storage;
        this.office = office;
        this.directory = directory;
        this.nodes = nodes;
        this.principals = principals;
        this.members = members;
        this.content = content;
        this.portal = portal;
        this.backups = backups;
        this.jobs = jobs;
        this.audit = audit;
        this.search = search;
        this.mail = mail;
        this.env = env;
    }

    public record Health(String component, String status, String detail) {}

    public record Overview(Map<String, Long> counts, long storedBytes, long versionBytes, List<Health> health,
                           Map<String, Object> server, List<AdminJob> runningJobs) {}

    public record SiteRow(UUID id, String name, String path, String title, String description, String template,
                          long files, long bytes, Instant createdAt, Instant lastModified, List<String> owners,
                          boolean subsite, boolean hub) {}

    // ---- overview ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Overview overview() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : em.createQuery(
                "select n.kind, count(n) from Node n where n.deletedAt is null group by n.kind", Object[].class).getResultList()) {
            counts.put(row[0].toString().toLowerCase(Locale.ROOT), (Long) row[1]);
        }
        for (String k : List.of("site", "library", "list", "folder", "file", "list_item")) counts.putIfAbsent(k, 0L);
        Object[] v = em.createQuery("select count(v), coalesce(sum(v.sizeBytes), 0) from FileVersion v", Object[].class).getSingleResult();
        counts.put("versions", (Long) v[0]);
        long versionBytes = ((Number) v[1]).longValue();
        long stored = ((Number) em.createQuery(
                "select coalesce(sum(n.sizeBytes), 0) from Node n where n.deletedAt is null and n.kind = io.nexora.content.Node.Kind.FILE")
                .getSingleResult()).longValue();
        counts.put("users", em.createQuery("select count(p) from Principal p where p.kind = io.nexora.identity.Principal.Kind.USER", Long.class).getSingleResult());
        counts.put("groups", em.createQuery("select count(p) from Principal p where p.kind = io.nexora.identity.Principal.Kind.GROUP", Long.class).getSingleResult());
        counts.put("pages", em.createQuery("select count(p) from SitePage p", Long.class).getSingleResult());
        counts.put("recycleBin", em.createQuery("select count(n) from Node n where n.deletedAt is not null and n.deletedRootId = n.id", Long.class).getSingleResult());

        Runtime rt = Runtime.getRuntime();
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("version", Optional.ofNullable(FarmService.class.getPackage().getImplementationVersion()).orElse("0.1.0-SNAPSHOT"));
        server.put("java", System.getProperty("java.version"));
        server.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        server.put("startedAt", Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()));
        server.put("uptimeSeconds", Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()).toSeconds());
        server.put("heapUsedBytes", rt.totalMemory() - rt.freeMemory());
        server.put("heapMaxBytes", rt.maxMemory());
        server.put("processors", rt.availableProcessors());

        List<AdminJob> running = jobs.latest(50).stream().filter(j -> !j.getStatus().isFinished()).toList();
        return new Overview(counts, stored, versionBytes, health(), server, running);
    }

    public List<Health> health() {
        List<Health> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            var md = c.getMetaData();
            out.add(new Health("Database", c.isValid(3) ? "OK" : "ERROR", md.getDatabaseProductName() + " " + md.getDatabaseProductVersion()));
        } catch (Exception e) {
            out.add(new Health("Database", "ERROR", e.getMessage()));
        }
        try {
            store.exists("sha256/00/00/health-probe");
            out.add(new Health("File storage", "OK", storage.type()));
        } catch (Exception e) {
            out.add(new Health("File storage", "ERROR", storage.type() + ": " + e.getMessage()));
        }
        WopiDiscovery wopi = office.getIfAvailable();
        if (wopi == null || !env.getProperty("nexora.office.enabled", Boolean.class, false)) {
            out.add(new Health("Office Online (Collabora)", "DISABLED", "Browser editing is turned off"));
        } else {
            out.add(new Health("Office Online (Collabora)", wopi.available() ? "OK" : "WARNING",
                    wopi.available() ? "Editor discovery loaded" : "The editor is not reachable"));
        }
        if (search.enabled()) {
            SearchAdminService.Component index = search.engine();
            out.add(new Health("Search index", index.ok() ? "OK" : "ERROR", index.name() + ": " + index.detail()));
            SearchAdminService.Component reader = search.extractor();
            out.add(new Health("Document reading", reader.ok() ? "OK" : "WARNING", reader.name() + ": " + reader.detail()));
        } else {
            out.add(new Health("Search", "DISABLED", "Turned off by an administrator"));
        }
        if (!mail.enabled()) {
            out.add(new Health("Email", "DISABLED", "Alerts and sharing notices are not sent"));
        } else {
            MailService.Status status = mail.status();
            String problem = mail.check();
            out.add(new Health("Email", problem != null || status.failed() > 0 ? "WARNING" : "OK", problem != null ? problem
                    : status.server() + ", " + status.waiting() + " waiting, " + status.failed() + " failed"));
        }
        DirectorySyncService sync = directory.getIfAvailable();
        if (sync == null || !sync.enabled()) {
            out.add(new Health("Directory sync", "DISABLED", "Single sign-on directory sync is off"));
        } else {
            DirectorySyncService.Status s = sync.status();
            out.add(new Health("Directory sync", s.error() == null ? "OK" : "WARNING",
                    s.error() != null ? s.error() : s.lastSuccess() == null ? "Not run yet"
                            : s.users() + " users, " + s.groups() + " groups, last " + s.lastSuccess()));
        }
        try {
            var dir = backups.root();
            Files.createDirectories(dir);
            long free = Files.getFileStore(dir).getUsableSpace();
            out.add(new Health("Backup folder", free < 1L << 30 ? "WARNING" : "OK",
                    dir + " (" + (free >> 30) + " GB free)"));
        } catch (Exception e) {
            out.add(new Health("Backup folder", "ERROR", e.getMessage()));
        }
        return out;
    }

    // ---- sites ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SiteRow> sites() {
        Map<UUID, Object[]> stats = new HashMap<>();
        for (Object[] row : em.createQuery("""
                select n.siteId, count(n), coalesce(sum(n.sizeBytes), 0), max(n.modifiedAt) from Node n
                where n.deletedAt is null and n.kind = io.nexora.content.Node.Kind.FILE group by n.siteId""", Object[].class)
                .getResultList()) {
            stats.put((UUID) row[0], row);
        }
        List<SiteRow> out = new ArrayList<>();
        for (Node s : nodes.findLiveSites()) {
            Object[] st = stats.get(s.getId());
            List<String> owners = principals.findByKindAndLogin(Principal.Kind.GROUP, s.groupPrefix() + "-owners")
                    .map(g -> {
                        List<UUID> ids = members.findByGroupId(g.getId()).stream().map(GroupMember::getMemberId).toList();
                        return principals.findAllById(ids).stream().map(Principal::getDisplayName).sorted().toList();
                    }).orElse(List.of());
            Instant last = st == null ? s.getModifiedAt() : later(s.getModifiedAt(), (Instant) st[3]);
            out.add(new SiteRow(s.getId(), s.getName(), s.getPath(), s.getTitle(), s.getDescription(),
                    Objects.toString(s.getSettings().get("template"), null),
                    st == null ? 0 : (Long) st[1], st == null ? 0 : ((Number) st[2]).longValue(),
                    s.getCreatedAt(), last, owners, s.getParentId() != null, io.nexora.portal.HubService.isHub(s)));
        }
        return out;
    }

    /** Creates a site from a template with the given person as its owner (site collection administrator). */
    @Transactional
    public Node createSite(CurrentUser actor, String url, String title, String description, SiteTemplate template,
                           String ownerLogin) {
        Principal owner = null;
        if (ownerLogin != null && !ownerLogin.isBlank()) {
            owner = principals.findByKindAndLogin(Principal.Kind.USER, ownerLogin.strip())
                    .orElseThrow(() -> ApiException.badRequest("unknown_owner", "There is no user '" + ownerLogin.strip() + "'"));
        }
        Node site = portal.createSite(actor, url, title, description, template);
        if (owner != null && !owner.getId().equals(actor.id())) {
            Principal owners = principals.findByKindAndLogin(Principal.Kind.GROUP, site.groupPrefix() + "-owners").orElseThrow();
            members.save(new GroupMember(owners.getId(), owner.getId()));
            members.findByGroupId(owners.getId()).stream()
                    .filter(m -> m.getMemberId().equals(actor.id())).forEach(members::delete);
        }
        return site;
    }

    @Transactional
    public void deleteSite(CurrentUser actor, UUID siteId) {
        Node site = nodes.findLive(siteId).filter(n -> n.getKind() == Node.Kind.SITE)
                .orElseThrow(() -> ApiException.notFound("Site"));
        content.delete(actor, site.getId());
        audit.record(actor, "SITE_DELETE", site, Map.of("by", "central-admin"));
    }

    /** Settings worth seeing at a glance; secrets are never shown. */
    public Map<String, String> settings() {
        Map<String, String> out = new LinkedHashMap<>();
        String[][] keys = {
                {"Storage", "nexora.storage.type"},
                {"Browser Office editing", "nexora.office.enabled"},
                {"Single sign-on (OIDC)", "nexora.identity.oidc.enabled"},
                {"Local accounts", "nexora.identity.local-login"},
                {"Desktop Office sign-in", "nexora.dav.office-sign-in"},
                {"Self-service site creation", "nexora.sites.self-service"},
                {"Backup folder", "nexora.admin.backup-dir"},
                {"Demo data", "nexora.bootstrap.demo-data"},
                {"Secure cookies", "server.servlet.session.cookie.secure"},
        };
        for (String[] k : keys) out.put(k[0], env.getProperty(k[1], "(default)"));
        out.put("Backup folder", backups.root().toString());
        return out;
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
