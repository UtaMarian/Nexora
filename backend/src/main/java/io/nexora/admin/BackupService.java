package io.nexora.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.GroupMember;
import io.nexora.identity.GroupMemberRepository;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.transfer.SitePackage;
import io.nexora.transfer.SiteTransferService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Site and farm backups on the server's backup directory, restores from them, and uploaded site packages
 * (export/import between farms).
 *
 * <p>Layout: {@code <backup-dir>/<id>/backup.json}, {@code sites/<site>.zip} (one {@link SitePackage} per
 * site) and, for farm backups, {@code directory.json} with users, groups and memberships.
 */
@Service
public class BackupService {
    private static final Pattern ID = Pattern.compile("[a-z0-9-]{1,120}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    public enum Kind { SITE, FARM, UPLOAD }

    public record SiteEntry(String name, String title, String file, long sizeBytes, int items, int versions) {}

    public record BackupInfo(String id, Kind kind, String label, Instant createdAt, String createdBy,
                             List<SiteEntry> sites, boolean includesDirectory, long sizeBytes) {}

    public record DirectoryBackup(List<UserRec> users, List<GroupRec> groups) {}

    public record UserRec(String login, String displayName, String email, String passwordHash, boolean admin,
                          boolean enabled, String externalId) {}

    public record GroupRec(String login, String displayName, String externalId, List<String> memberUsers,
                           List<String> memberGroups) {}

    /** What to restore from a backup: a site, optionally under another URL and title. */
    public record SiteRestore(String name, String targetName, String title) {}

    private final Path root;
    private final SiteTransferService transfer;
    private final JobService jobs;
    private final NodeRepository nodes;
    private final PrincipalRepository principals;
    private final GroupMemberRepository members;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public BackupService(@Value("${nexora.admin.backup-dir:./data/backups}") String dir, SiteTransferService transfer,
                         JobService jobs, NodeRepository nodes, PrincipalRepository principals,
                         GroupMemberRepository members, AuditService audit, PlatformTransactionManager txManager,
                         ObjectMapper json) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.transfer = transfer;
        this.jobs = jobs;
        this.nodes = nodes;
        this.principals = principals;
        this.members = members;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path root() {
        return root;
    }

    // ---- backups -------------------------------------------------------------------------------

    public AdminJob backupSite(CurrentUser actor, UUID siteId) {
        Node site = nodes.findLive(siteId).filter(n -> n.getKind() == Node.Kind.SITE)
                .orElseThrow(() -> ApiException.notFound("Site"));
        String id = STAMP.format(Instant.now()) + "-site-" + site.getName();
        return jobs.submit("SITE_BACKUP", "Back up " + site.getPath(), Map.of("siteId", siteId.toString(), "backupId", id), actor, ctx -> {
            Path dir = Files.createDirectories(root.resolve(id).resolve("sites"));
            SiteTransferService.ExportResult r = transfer.export(siteId, actor.login(), dir.resolve(site.getName() + ".zip"), ctx::log);
            write(new BackupInfo(id, Kind.SITE, "Site backup of " + site.getPath(), Instant.now(), actor.login(),
                    List.of(entry(r)), false, r.bytes()));
            audit.record(actor, "SITE_BACKUP", site, Map.of("backup", id));
            return Map.of("backupId", id, "items", r.nodes(), "versions", r.versions(), "bytes", r.bytes());
        });
    }

    public AdminJob backupFarm(CurrentUser actor) {
        String id = STAMP.format(Instant.now()) + "-farm";
        return jobs.submit("FARM_BACKUP", "Back up the farm", Map.of("backupId", id), actor, ctx -> {
            Path dir = Files.createDirectories(root.resolve(id).resolve("sites"));
            DirectoryBackup directory = tx.execute(s -> directory());
            json.writeValue(root.resolve(id).resolve("directory.json").toFile(), directory);
            ctx.log("Saved the directory: " + directory.users().size() + " users, " + directory.groups().size() + " groups");
            List<Node> sites = nodes.findLiveSites();
            List<SiteEntry> entries = new ArrayList<>();
            long bytes = 0;
            for (int i = 0; i < sites.size(); i++) {
                ctx.checkCancelled();
                Node site = sites.get(i);
                SiteTransferService.ExportResult r = transfer.export(site.getId(), actor.login(), dir.resolve(site.getName() + ".zip"), ctx::log);
                entries.add(entry(r));
                bytes += r.bytes();
                ctx.progress((i + 1) * 100 / sites.size());
                write(new BackupInfo(id, Kind.FARM, "Farm backup (in progress)", Instant.now(), actor.login(), entries, true, bytes));
            }
            write(new BackupInfo(id, Kind.FARM, "Farm backup: " + sites.size() + " sites", Instant.now(), actor.login(),
                    entries, true, bytes));
            return Map.of("backupId", id, "sites", sites.size(), "bytes", bytes);
        });
    }

    /** Stores an uploaded site package (an export from this or another farm) so it can be restored. */
    public BackupInfo upload(CurrentUser actor, InputStream data, String fileName) throws IOException {
        String id = STAMP.format(Instant.now()) + "-import-" + UUID.randomUUID().toString().substring(0, 6);
        Path dir = Files.createDirectories(root.resolve(id).resolve("sites"));
        Path tmp = dir.resolve("upload.tmp");
        Files.copy(data, tmp, StandardCopyOption.REPLACE_EXISTING);
        SitePackage pkg;
        try {
            pkg = transfer.readManifest(tmp);
        } catch (IOException | RuntimeException e) {
            deleteTree(root.resolve(id));
            if (e instanceof ApiException a) throw a;
            throw ApiException.badRequest("invalid_package", "This is not a NEXORA site package");
        }
        Path file = dir.resolve(pkg.site().name() + ".zip");
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        int versions = pkg.nodes().stream().mapToInt(n -> n.versions().size()).sum();
        BackupInfo info = new BackupInfo(id, Kind.UPLOAD, "Imported package " + (fileName == null ? "" : fileName).strip(),
                Instant.now(), actor.login(),
                List.of(new SiteEntry(pkg.site().name(), pkg.site().title(), file.getFileName().toString(), Files.size(file),
                        pkg.nodes().size(), versions)), false, Files.size(file));
        write(info);
        return info;
    }

    public List<BackupInfo> list() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        List<BackupInfo> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                Path meta = d.resolve("backup.json");
                if (Files.exists(meta)) {
                    try {
                        out.add(json.readValue(meta.toFile(), BackupInfo.class));
                    } catch (IOException ignored) {
                        // a damaged backup.json: leave it for the administrator to inspect on disk
                    }
                }
            }
        }
        out.sort(Comparator.comparing(BackupInfo::createdAt).reversed());
        return out;
    }

    public BackupInfo get(String id) throws IOException {
        Path meta = dir(id).resolve("backup.json");
        if (!Files.exists(meta)) throw ApiException.notFound("Backup");
        return json.readValue(meta.toFile(), BackupInfo.class);
    }

    public Path sitePackage(String id, String site) throws IOException {
        BackupInfo info = get(id);
        SiteEntry e = info.sites().stream().filter(s -> s.name().equalsIgnoreCase(site)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Site in backup"));
        Path file = dir(id).resolve("sites").resolve(e.file()).normalize();
        if (!file.startsWith(dir(id)) || !Files.exists(file)) throw ApiException.notFound("Backup file");
        return file;
    }

    public void delete(CurrentUser actor, String id) throws IOException {
        get(id);
        deleteTree(dir(id));
        audit.record(actor, "BACKUP_DELETE", null, Map.of("backup", id));
    }

    // ---- restore -------------------------------------------------------------------------------

    public AdminJob restore(CurrentUser actor, String backupId, List<SiteRestore> sites, boolean directory) throws IOException {
        BackupInfo info = get(backupId);
        if ((sites == null || sites.isEmpty()) && !directory) {
            throw ApiException.badRequest("nothing_selected", "Choose at least one site to restore");
        }
        if (directory && !info.includesDirectory()) {
            throw ApiException.badRequest("no_directory", "This backup has no directory (users and groups)");
        }
        List<SiteRestore> chosen = sites == null ? List.of() : sites;
        List<Path> files = new ArrayList<>();
        for (SiteRestore s : chosen) files.add(sitePackage(backupId, s.name()));
        return jobs.submit("RESTORE", "Restore from " + backupId,
                Map.of("backupId", backupId, "sites", chosen.stream().map(SiteRestore::name).toList()), actor, ctx -> {
            List<Map<String, Object>> restored = new ArrayList<>();
            if (directory) {
                DirectoryBackup d = json.readValue(dir(backupId).resolve("directory.json").toFile(), DirectoryBackup.class);
                int[] counts = tx.execute(s -> restoreDirectory(d));
                ctx.log("Directory: added " + counts[0] + " users, " + counts[1] + " groups and " + counts[2] + " memberships");
            }
            for (int i = 0; i < chosen.size(); i++) {
                ctx.checkCancelled();
                SiteRestore s = chosen.get(i);
                String target = s.targetName() == null || s.targetName().isBlank() ? s.name() : s.targetName();
                if (nodes.existsSiteName(target)) {
                    ctx.log("Skipped " + s.name() + ": /sites/" + target + " already exists. Restore it under another URL.");
                    continue;
                }
                SiteTransferService.ImportResult r = transfer.importSite(files.get(i), target, s.title(), actor, ctx::log);
                restored.add(Map.of("site", r.path(), "items", r.nodes(), "versions", r.versions(), "warnings", r.warnings().size()));
                ctx.progress((i + 1) * 100 / chosen.size());
            }
            return Map.of("restored", restored);
        });
    }

    // ---- helpers -------------------------------------------------------------------------------

    private DirectoryBackup directory() {
        List<UserRec> users = new ArrayList<>();
        List<GroupRec> groups = new ArrayList<>();
        Map<UUID, Principal> all = new HashMap<>();
        principals.findAll().forEach(p -> all.put(p.getId(), p));
        for (Principal p : all.values()) {
            if (!p.isGroup()) {
                users.add(new UserRec(p.getLogin(), p.getDisplayName(), p.getEmail(), p.getPasswordHash(), p.isAdmin(),
                        p.isEnabled(), p.getExternalId()));
            } else if (p.getSiteId() == null) {
                List<String> u = new ArrayList<>();
                List<String> g = new ArrayList<>();
                for (GroupMember m : members.findByGroupId(p.getId())) {
                    Principal member = all.get(m.getMemberId());
                    if (member == null) continue;
                    (member.isGroup() ? g : u).add(member.getLogin());
                }
                groups.add(new GroupRec(p.getLogin(), p.getDisplayName(), p.getExternalId(), u, g));
            }
        }
        users.sort(Comparator.comparing(UserRec::login));
        groups.sort(Comparator.comparing(GroupRec::login));
        return new DirectoryBackup(users, groups);
    }

    /** Adds missing users, groups and memberships; never changes accounts that exist. */
    private int[] restoreDirectory(DirectoryBackup d) {
        int users = 0, groups = 0, memberships = 0;
        for (UserRec u : d.users()) {
            if (principals.findByKindAndLogin(Principal.Kind.USER, u.login()).isPresent()) continue;
            Principal p = Principal.user(u.login(), u.displayName(), u.passwordHash());
            p.setEmail(u.email());
            p.setAdmin(u.admin());
            p.setEnabled(u.enabled());
            p.setExternalId(u.externalId());
            principals.save(p);
            users++;
        }
        for (GroupRec g : d.groups()) {
            if (principals.findByKindAndLogin(Principal.Kind.GROUP, g.login()).isPresent()) continue;
            Principal p = Principal.group(g.login(), g.displayName(), null);
            p.setExternalId(g.externalId());
            principals.save(p);
            groups++;
        }
        principals.flush();
        for (GroupRec g : d.groups()) {
            Principal group = principals.findByKindAndLogin(Principal.Kind.GROUP, g.login()).orElseThrow();
            for (String login : g.memberUsers()) memberships += addMember(group, Principal.Kind.USER, login);
            for (String login : g.memberGroups()) memberships += addMember(group, Principal.Kind.GROUP, login);
        }
        return new int[] {users, groups, memberships};
    }

    private int addMember(Principal group, Principal.Kind kind, String login) {
        Optional<Principal> m = principals.findByKindAndLogin(kind, login);
        if (m.isEmpty() || members.existsByGroupIdAndMemberId(group.getId(), m.get().getId())) return 0;
        members.save(new GroupMember(group.getId(), m.get().getId()));
        return 1;
    }

    private static SiteEntry entry(SiteTransferService.ExportResult r) {
        return new SiteEntry(r.siteName(), r.title(), r.siteName() + ".zip", r.bytes(), r.nodes(), r.versions());
    }

    private void write(BackupInfo info) throws IOException {
        Path dir = Files.createDirectories(root.resolve(info.id()));
        Path tmp = dir.resolve("backup.json.tmp");
        json.writeValue(tmp.toFile(), info);
        Files.move(tmp, dir.resolve("backup.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path dir(String id) {
        if (id == null || !ID.matcher(id).matches()) throw ApiException.notFound("Backup");
        return root.resolve(id);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
