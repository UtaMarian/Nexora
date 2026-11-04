package io.nexora.migration;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.acl.AclEntry;
import io.nexora.acl.AclEntryRepository;
import io.nexora.acl.PermissionLevel;
import io.nexora.admin.JobService;
import io.nexora.audit.AuditService;
import io.nexora.content.*;
import io.nexora.identity.CurrentUser;
import io.nexora.search.SearchQueue;
import io.nexora.identity.GroupMember;
import io.nexora.identity.GroupMemberRepository;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.portal.PortalService;
import io.nexora.portal.SiteTemplate;
import io.nexora.storage.BlobStore;
import io.nexora.storage.StoredBlob;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static io.nexora.migration.SharePointClient.literal;
import static io.nexora.migration.SharePointClient.query;

/**
 * Scans a SharePoint site and copies it into NEXORA: document libraries with folders, files and their version
 * history, lists and items, custom columns and their values, authors and dates, and permissions (site, list
 * and item level, SharePoint groups, AD users and groups). Every copied object is recorded in
 * {@code migration_item}, so running the task again only copies what changed (delta sync) and old SharePoint
 * links can be redirected.
 */
@Service
public class MigrationEngine {
    /** List templates NEXORA copies as document libraries. */
    static final Set<Integer> LIBRARIES = Set.of(101, 109, 700, 851);
    /** List templates NEXORA copies as lists, with the matching NEXORA list template. */
    static final Map<Integer, ListTemplate> LISTS = Map.of(100, ListTemplate.CUSTOM, 103, ListTemplate.LINKS,
            104, ListTemplate.ANNOUNCEMENTS, 105, ListTemplate.CONTACTS, 106, ListTemplate.EVENTS,
            107, ListTemplate.TASKS, 171, ListTemplate.TASKS, 1100, ListTemplate.ISSUES);
    static final int MAX_PATH = 400;

    private final NodeRepository nodes;
    private final FileVersionRepository versions;
    private final ColumnDefRepository columns;
    private final AclEntryRepository acl;
    private final PrincipalRepository principals;
    private final GroupMemberRepository members;
    private final PortalService portal;
    private final BlobStore store;
    private final MigrationItemRepository items;
    private final AuditService audit;
    private final SearchQueue search;
    private final TransactionTemplate tx;

    public MigrationEngine(NodeRepository nodes, FileVersionRepository versions, ColumnDefRepository columns,
                           AclEntryRepository acl, PrincipalRepository principals, GroupMemberRepository members,
                           PortalService portal, BlobStore store, MigrationItemRepository items, AuditService audit, SearchQueue search,
                           PlatformTransactionManager txManager) {
        this.nodes = nodes;
        this.versions = versions;
        this.columns = columns;
        this.acl = acl;
        this.principals = principals;
        this.members = members;
        this.portal = portal;
        this.store = store;
        this.items = items;
        this.audit = audit;
        this.search = search;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Task options, read from the task's JSON. */
    public record Options(int maxVersions, boolean permissions, boolean metadata, boolean deleteMissing,
                          Map<String, String> userMappings, Set<String> lists) {
        @SuppressWarnings("unchecked")
        public static Options of(Map<String, Object> m) {
            Map<String, Object> o = m == null ? Map.of() : m;
            Object v = o.getOrDefault("versions", "all");
            int max = "all".equals(v) || v == null ? -1 : "none".equals(v) ? 0 : v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
            Map<String, String> users = new HashMap<>();
            if (o.get("userMappings") instanceof Map<?, ?> um) um.forEach((k, val) -> users.put(norm(k.toString()), val.toString()));
            Set<String> lists = null;
            if (o.get("lists") instanceof Collection<?> c && !c.isEmpty()) {
                lists = new HashSet<>();
                for (Object x : c) lists.add(x.toString().toLowerCase(Locale.ROOT));
            }
            return new Options(max, !Boolean.FALSE.equals(o.get("permissions")), !Boolean.FALSE.equals(o.get("metadata")),
                    Boolean.TRUE.equals(o.get("deleteMissing")), users, lists);
        }
    }

    // ==== scan ===================================================================================

    /** Inventories a site: lists and libraries, what can be copied, and (deep) every file's issues. */
    public Map<String, Object> scan(SharePointClient sp, String webPath, boolean deep, JobService.Context ctx) throws IOException {
        JsonNode web = sp.json(sp.web(webPath, "/_api/web?$select=Id,Title,Description,ServerRelativeUrl,Created,WebTemplate,Configuration,LastItemModifiedDate,UIVersion"));
        ctx.log("Connected to '" + web.path("Title").asText() + "' (" + web.path("ServerRelativeUrl").asText() + ")");
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> subwebs = new ArrayList<>();
        for (JsonNode w : sp.all(sp.web(webPath, "/_api/web/webs?$select=Title,ServerRelativeUrl"))) {
            subwebs.add(Map.of("title", w.path("Title").asText(), "url", w.path("ServerRelativeUrl").asText()));
        }
        if (!subwebs.isEmpty()) warnings.add(subwebs.size() + " subsite(s) are not copied with this site: create a task for each");

        List<Map<String, Object>> lists = new ArrayList<>();
        long files = 0, folders = 0, bytes = 0, items = 0, unique = 0;
        for (JsonNode l : lists(sp, webPath)) {
            ctx.checkCancelled();
            int template = l.path("BaseTemplate").asInt();
            String kind = LIBRARIES.contains(template) ? "library" : LISTS.containsKey(template) ? "list" : "unsupported";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", l.path("Id").asText());
            row.put("title", l.path("Title").asText());
            row.put("url", l.path("RootFolder").path("ServerRelativeUrl").asText());
            row.put("baseTemplate", template);
            row.put("kind", kind);
            row.put("itemCount", l.path("ItemCount").asLong());
            row.put("uniquePermissions", l.path("HasUniqueRoleAssignments").asBoolean());
            row.put("versioning", l.path("EnableVersioning").asBoolean());
            if (l.path("HasUniqueRoleAssignments").asBoolean()) unique++;
            items += l.path("ItemCount").asLong();
            if (kind.equals("unsupported")) {
                row.put("note", template == 119 ? "Wiki and site pages are not migrated" : "This list type is not supported yet");
            }
            if (deep && kind.equals("library")) {
                long[] t = new long[3];   // files, folders, bytes
                walkForScan(sp, webPath, row.get("url").toString(), true, t, issues, ctx);
                row.put("files", t[0]);
                row.put("folders", t[1]);
                row.put("bytes", t[2]);
                files += t[0];
                folders += t[1];
                bytes += t[2];
            }
            lists.add(row);
        }
        long unsupported = lists.stream().filter(r -> "unsupported".equals(r.get("kind"))).count();
        if (unsupported > 0) warnings.add(unsupported + " list(s) of unsupported types are skipped");
        if (issues.size() >= 500) warnings.add("More than 500 file issues: only the first 500 are listed");

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("libraries", lists.stream().filter(r -> "library".equals(r.get("kind"))).count());
        totals.put("lists", lists.stream().filter(r -> "list".equals(r.get("kind"))).count());
        totals.put("items", items);
        totals.put("uniquePermissions", unique);
        if (deep) {
            totals.put("files", files);
            totals.put("folders", folders);
            totals.put("bytes", bytes);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scannedAt", Instant.now().toString());
        report.put("deep", deep);
        report.put("web", Map.of("id", web.path("Id").asText(), "title", web.path("Title").asText(),
                "url", web.path("ServerRelativeUrl").asText(), "template", web.path("WebTemplate").asText() + "#" + web.path("Configuration").asText(),
                "created", web.path("Created").asText(), "lastModified", web.path("LastItemModifiedDate").asText()));
        report.put("subwebs", subwebs);
        report.put("lists", lists);
        report.put("totals", totals);
        report.put("warnings", warnings);
        report.put("issues", issues.size() > 500 ? issues.subList(0, 500) : issues);
        ctx.log("Scan finished: " + lists.size() + " lists and libraries, " + items + " items"
                + (deep ? ", " + files + " files" : "") + ", " + warnings.size() + " warnings");
        return report;
    }

    private void walkForScan(SharePointClient sp, String webPath, String folderUrl, boolean root, long[] t,
                             List<Map<String, Object>> issues, JobService.Context ctx) throws IOException {
        ctx.checkCancelled();
        JsonNode f = sp.json(sp.web(webPath, "/_api/web/GetFolderByServerRelativeUrl(@u)?@u=" + literal(folderUrl)
                + "&$expand=Folders,Files"));
        for (JsonNode sub : f.path("Folders")) {
            String name = sub.path("Name").asText();
            if (root && name.equalsIgnoreCase("Forms")) continue;
            t[1]++;
            checkName(sub.path("ServerRelativeUrl").asText(), name, issues);
            walkForScan(sp, webPath, sub.path("ServerRelativeUrl").asText(), false, t, issues, ctx);
        }
        for (JsonNode file : f.path("Files")) {
            t[0]++;
            t[2] += file.path("Length").asLong();
            String url = file.path("ServerRelativeUrl").asText();
            checkName(url, file.path("Name").asText(), issues);
            if (file.path("CheckOutType").asInt(2) != 2) {
                issue(issues, url, "Checked out: only the last checked-in version is copied");
            }
        }
    }

    private static void checkName(String url, String name, List<Map<String, Object>> issues) {
        try {
            Names.item(name);
        } catch (RuntimeException e) {
            issue(issues, url, "Name is not allowed in NEXORA: " + e.getMessage());
        }
        if (url.length() > MAX_PATH) issue(issues, url, "Path is longer than " + MAX_PATH + " characters");
    }

    private static void issue(List<Map<String, Object>> issues, String url, String text) {
        if (issues.size() < 501) issues.add(Map.of("url", url, "issue", text));
    }

    private List<JsonNode> lists(SharePointClient sp, String webPath) throws IOException {
        return sp.all(sp.web(webPath, "/_api/web/lists?$select=Id,Title,Description,BaseTemplate,BaseType,Hidden,ItemCount,"
                + "HasUniqueRoleAssignments,EnableVersioning,EnableMinorVersions,RootFolder/ServerRelativeUrl,RootFolder/Name"
                + "&$expand=RootFolder&$filter=" + query("Hidden eq false")));
    }

    // ==== copy ===================================================================================

    public record Stats(int created, int updated, int unchanged, int versions, long bytes, int deleted, int failed,
                        int warnings) {}

    /** Copies the site; on later runs only new and changed content (delta). */
    public Stats copy(SharePointClient sp, MigrationTask task, Options options, CurrentUser actor, JobService.Context ctx)
            throws IOException {
        Run run = new Run(sp, task, options, actor, ctx);
        JsonNode web = sp.json(sp.web(task.getSourceWeb(), "/_api/web?$select=Id,Title,Description,ServerRelativeUrl,Created"));
        run.site = tx.execute(s -> ensureSite(run, web));
        ctx.log("Target site: " + run.site.getPath());
        try {
            return transfer(sp, task, options, actor, ctx, run);
        } finally {
            // also after a failed or cancelled run: whatever was copied should be searchable
            tx.executeWithoutResult(s -> search.subtree(run.site.getId()));
        }
    }

    private Stats transfer(SharePointClient sp, MigrationTask task, Options options, CurrentUser actor,
                           JobService.Context ctx, Run run) throws IOException {
        if (options.permissions()) run.guarded("site permissions", () -> sitePermissions(run));

        List<JsonNode> all = lists(sp, task.getSourceWeb());
        List<JsonNode> chosen = all.stream()
                .filter(l -> LIBRARIES.contains(l.path("BaseTemplate").asInt()) || LISTS.containsKey(l.path("BaseTemplate").asInt()))
                .filter(l -> options.lists() == null || options.lists().contains(l.path("Title").asText().toLowerCase(Locale.ROOT)))
                .toList();
        for (int i = 0; i < chosen.size(); i++) {
            ctx.checkCancelled();
            JsonNode l = chosen.get(i);
            ctx.log("Copying " + (LIBRARIES.contains(l.path("BaseTemplate").asInt()) ? "library" : "list") + " '"
                    + l.path("Title").asText() + "' (" + l.path("ItemCount").asLong() + " items)");
            run.guarded("'" + l.path("Title").asText() + "'", () -> {
                // learn what is restricted first: if this fails, nothing of this list is copied
                List<JsonNode> restricted = options.permissions() ? restrictedItems(run, l) : List.of();
                if (LIBRARIES.contains(l.path("BaseTemplate").asInt())) copyLibrary(run, l);
                else copyList(run, l);
                if (options.permissions()) listPermissions(run, l, restricted);
            });
            ctx.progress((i + 1) * 95 / Math.max(1, chosen.size()));
        }

        if (options.deleteMissing() && options.lists() == null && run.failed == 0) {
            tx.executeWithoutResult(s -> deleteMissing(run));
        } else if (options.deleteMissing()) {
            ctx.log("Skipped removing deleted items: the run had errors or was limited to some lists");
        }
        tx.executeWithoutResult(s -> audit.record(actor, "MIGRATION_COPY", run.site, Map.of("source", sp.base() + task.getSourceWeb(),
                "created", run.created, "updated", run.updated)));
        ctx.log("Done: " + run.created + " created, " + run.updated + " updated, " + run.unchanged + " unchanged, "
                + run.versions + " versions, " + run.deleted + " removed, " + run.failed + " failed");
        return new Stats(run.created, run.updated, run.unchanged, run.versions, run.bytes, run.deleted, run.failed, run.warnings);
    }

    private Node ensureSite(Run run, JsonNode web) {
        Node site = nodes.findLiveByPath("/sites/" + run.task.getTargetSite()).orElse(null);
        if (site == null) {
            site = portal.createSite(run.actor, run.task.getTargetSite(), web.path("Title").asText(run.task.getTargetSite()),
                    web.path("Description").asText(null), SiteTemplate.TEAM);
            run.ctx.log("Created the site " + site.getPath());
        }
        map(run, "SITE", web.path("Id").asText(), web.path("ServerRelativeUrl").asText(run.task.getSourceWeb()), site, null, null);
        return site;
    }

    // ---- libraries ----

    private void copyLibrary(Run run, JsonNode list) throws IOException {
        String listId = list.path("Id").asText();
        String rootUrl = list.path("RootFolder").path("ServerRelativeUrl").asText();
        String name = list.path("RootFolder").path("Name").asText();
        Node lib = tx.execute(s -> {
            Node n = nodes.findLiveByPath(run.site.getPath() + "/" + name).orElse(null);
            if (n != null && n.getKind() != Node.Kind.LIBRARY) throw new IllegalStateException("'" + name + "' exists and is not a library");
            if (n == null) n = Node.create(Node.Kind.LIBRARY, run.site, Names.item(name), run.actor.id());
            n.setTitle(list.path("Title").asText(name));
            n.setDescription(blankToNull(list.path("Description").asText(null)));
            new LibrarySettings(list.path("EnableVersioning").asBoolean(true), list.path("EnableMinorVersions").asBoolean(false),
                    false, null).applyTo(n);
            lockIfRestricted(run, n, list.path("HasUniqueRoleAssignments").asBoolean());
            nodes.save(n);
            map(run, "LIBRARY", listId, rootUrl, n, null, null);
            return n;
        });
        Map<String, Field> fields = run.options.metadata() ? ensureColumns(run, listId, lib) : Map.of();
        walk(run, rootUrl, lib, fields, true);
    }

    private void walk(Run run, String folderUrl, Node parent, Map<String, Field> fields, boolean root) throws IOException {
        run.ctx.checkCancelled();
        JsonNode f = run.sp.json(run.sp.web(run.task.getSourceWeb(), "/_api/web/GetFolderByServerRelativeUrl(@u)?@u="
                + literal(folderUrl) + "&$expand=Folders,Files,Files/Author,Files/ModifiedBy,Files/ListItemAllFields"));
        for (JsonNode sub : f.path("Folders")) {
            String name = sub.path("Name").asText();
            if (root && name.equalsIgnoreCase("Forms")) continue;
            Node folder = run.guardedValue("folder " + sub.path("ServerRelativeUrl").asText(), () -> tx.execute(s -> {
                String key = sub.path("UniqueId").asText();
                run.seen.add(key);
                Node n = mapped(run, key).orElse(null);
                if (n == null) {
                    n = nodes.findLiveByPath(parent.getPath() + "/" + name).orElse(null);
                    if (n == null) {
                        n = Node.create(Node.Kind.FOLDER, parent, Names.item(name), run.actor.id());
                        n.importAudit(null, instant(sub.path("TimeCreated")), null, instant(sub.path("TimeLastModified")));
                        lockIfRestricted(run, n, run.restricted.contains(sub.path("ServerRelativeUrl").asText().toLowerCase(Locale.ROOT)));
                        nodes.save(n);
                        run.created++;
                    }
                }
                map(run, "FOLDER", key, sub.path("ServerRelativeUrl").asText(), n, null, null);
                return n;
            }));
            if (folder != null) walk(run, sub.path("ServerRelativeUrl").asText(), folder, fields, false);
        }
        for (JsonNode file : f.path("Files")) {
            run.guarded("file " + file.path("ServerRelativeUrl").asText(), () -> copyFile(run, file, parent, fields));
        }
    }

    private void copyFile(Run run, JsonNode file, Node parent, Map<String, Field> fields) throws IOException {
        String key = file.path("UniqueId").asText();
        String url = file.path("ServerRelativeUrl").asText();
        String name = Names.item(file.path("Name").asText());
        Instant modified = instant(file.path("TimeLastModified"));
        String label = file.path("UIVersionLabel").asText("1.0");
        run.seen.add(key);
        MigrationItem known = items.findByTaskIdAndSourceKey(run.task.getId(), key).orElse(null);
        Node existing = known == null ? null : nodes.findLive(known.getNodeId()).orElse(null);
        if (existing != null && Objects.equals(known.getSourceModified(), modified) && url.equalsIgnoreCase(known.getSourceUrl())) {
            run.unchanged++;
            return;
        }
        if (existing == null && nodes.findLiveByPath(parent.getPath() + "/" + name).isPresent()) {
            run.warn("Skipped " + url + ": an item with that name already exists in NEXORA");
            return;
        }

        // versions to bring over: history newer than what we have, then the current version
        int[] have = existing == null ? null : parseLabel(known.getSourceVersion());
        List<JsonNode> history = new ArrayList<>();
        if (run.options.maxVersions() != 0) {
            for (JsonNode v : run.sp.all(run.sp.web(run.task.getSourceWeb(), "/_api/web/GetFileByServerRelativeUrl(@u)/Versions?@u="
                    + literal(url) + "&$expand=CreatedBy"))) {
                if (have == null || compare(parseLabel(v.path("VersionLabel").asText()), have) > 0) history.add(v);
            }
            history.sort((a, b) -> compare(parseLabel(a.path("VersionLabel").asText()), parseLabel(b.path("VersionLabel").asText())));
            if (run.options.maxVersions() > 0 && history.size() > run.options.maxVersions()) {
                history = new ArrayList<>(history.subList(history.size() - run.options.maxVersions(), history.size()));
            }
        }
        List<Downloaded> downloads = new ArrayList<>();
        try {
            for (JsonNode v : history) {
                Path tmp = Files.createTempFile("nexora-mig", ".bin");
                downloads.add(new Downloaded(tmp, parseLabel(v.path("VersionLabel").asText()), v.path("CheckInComment").asText(null),
                        v.path("CreatedBy"), instant(v.path("Created"))));
                run.sp.download(run.sp.absolute(run.task.getSourceWeb(), relativeToWeb(run, v.path("Url").asText())), tmp);
            }
            boolean currentIsNew = have == null || compare(parseLabel(label), have) > 0 || downloads.isEmpty();
            if (currentIsNew) {
                Path tmp = Files.createTempFile("nexora-mig", ".bin");
                downloads.add(new Downloaded(tmp, parseLabel(label), file.path("CheckInComment").asText(null),
                        file.path("ModifiedBy"), modified));
                run.sp.download(run.sp.web(run.task.getSourceWeb(), "/_api/web/GetFileByServerRelativeUrl(@u)/$value?@u=" + literal(url)), tmp);
            }
            List<StoredBlob> blobs = new ArrayList<>();
            for (Downloaded d : downloads) blobs.add(store.put(d.file()));

            tx.executeWithoutResult(s -> {
                Node n = existing == null ? null : nodes.findLive(existing.getId()).orElse(null);
                if (n == null) {
                    n = Node.create(Node.Kind.FILE, parent, name, run.actor.id());
                    lockIfRestricted(run, n, run.restricted.contains(url.toLowerCase(Locale.ROOT)));
                    nodes.saveAndFlush(n);
                    run.created++;
                } else {
                    if (!n.getParentId().equals(parent.getId()) || !n.getName().equals(name)) {
                        // moved or renamed in SharePoint
                        n.setParentId(parent.getId());
                        n.setName(name);
                        n.setPath(parent.getPath() + "/" + name);
                    }
                    run.updated++;
                }
                Set<String> labels = new HashSet<>();
                versions.findHistory(n.getId()).forEach(v -> labels.add(v.label()));
                String mime = MimeTypes.resolve(name, null);
                FileVersion last = null;
                for (int i = 0; i < downloads.size(); i++) {
                    Downloaded d = downloads.get(i);
                    StoredBlob b = blobs.get(i);
                    if (!labels.add(d.label()[0] + "." + d.label()[1])) continue;
                    last = versions.save(FileVersion.imported(n.getId(), d.label()[0], d.label()[1], b.key(), b.sizeBytes(),
                            b.sha256(), mime, name, blankToNull(d.comment()), person(run, d.author()), d.at()));
                    run.versions++;
                    run.bytes += b.sizeBytes();
                }
                if (last != null) {
                    n.setCurrentVersionId(last.getId());
                    n.setSizeBytes(last.getSizeBytes());
                    n.setMimeType(mime);
                }
                if (run.options.metadata()) n.setMetadata(values(run, file.path("ListItemAllFields"), fields, n.getMetadata()));
                n.importAudit(person(run, file.path("Author")), instant(file.path("TimeCreated")), person(run, file.path("ModifiedBy")), modified);
                nodes.save(n);
                map(run, "FILE", key, url, n, modified, label);
                run.listItemIndex.put(url.toLowerCase(Locale.ROOT), n);
            });
        } finally {
            for (Downloaded d : downloads) Files.deleteIfExists(d.file());
        }
    }

    private record Downloaded(Path file, int[] label, String comment, JsonNode author, Instant at) {}

    // ---- lists ----

    private void copyList(Run run, JsonNode list) throws IOException {
        String listId = list.path("Id").asText();
        String rootUrl = list.path("RootFolder").path("ServerRelativeUrl").asText();
        String name = list.path("RootFolder").path("Name").asText();
        ListTemplate template = LISTS.get(list.path("BaseTemplate").asInt());
        Node container = tx.execute(s -> {
            Node n = nodes.findLiveByPath(run.site.getPath() + "/" + name).orElse(null);
            if (n != null && n.getKind() != Node.Kind.LIST) throw new IllegalStateException("'" + name + "' exists and is not a list");
            if (n == null) {
                n = Node.create(Node.Kind.LIST, run.site, Names.item(name), run.actor.id());
                Map<String, Object> settings = new HashMap<>();
                settings.put("template", template.name());
                settings.put("nextItemId", 1);
                n.setSettings(settings);
            }
            lockIfRestricted(run, n, list.path("HasUniqueRoleAssignments").asBoolean());
            n.setTitle(list.path("Title").asText(name));
            n.setDescription(blankToNull(list.path("Description").asText(null)));
            nodes.save(n);
            map(run, "LIST", listId, rootUrl, n, null, null);
            return n;
        });
        Map<String, Field> fields = run.options.metadata() ? ensureColumns(run, listId, container) : Map.of();
        int attachments = 0;
        int maxId = 0;
        for (JsonNode item : run.sp.all(run.sp.web(run.task.getSourceWeb(), "/_api/web/lists(guid'" + listId
                + "')/items?$top=500&$select=*,Author/LoginName,Author/Title,Author/EMail,Editor/LoginName,Editor/Title,Editor/EMail"
                + "&$expand=Author,Editor"))) {
            run.ctx.checkCancelled();
            int id = item.path("ID").asInt(item.path("Id").asInt());
            maxId = Math.max(maxId, id);
            if (item.path("Attachments").asBoolean(false)) attachments++;
            String key = listId + ":" + id;
            run.seen.add(key);
            Instant modified = instant(item.path("Modified"));
            run.guarded("item " + id + " of '" + list.path("Title").asText() + "'", () -> tx.executeWithoutResult(s -> {
                MigrationItem known = items.findByTaskIdAndSourceKey(run.task.getId(), key).orElse(null);
                Node n = known == null ? null : nodes.findLive(known.getNodeId()).orElse(null);
                if (n != null && Objects.equals(known.getSourceModified(), modified)) {
                    run.unchanged++;
                    return;
                }
                if (n == null) {
                    n = nodes.findLiveByPath(container.getPath() + "/" + id).orElse(null);
                    if (n == null) {
                        n = Node.create(Node.Kind.LIST_ITEM, container, String.valueOf(id), run.actor.id());
                        lockIfRestricted(run, n, run.restricted.contains(key.toLowerCase(Locale.ROOT)));
                        run.created++;
                    } else {
                        run.updated++;
                    }
                } else {
                    run.updated++;
                }
                String title = item.path("Title").asText("");
                n.setTitle(title.isBlank() ? "(no title)" : title.strip());
                if (run.options.metadata()) n.setMetadata(values(run, item, fields, n.getMetadata()));
                n.importAudit(person(run, item.path("Author")), instant(item.path("Created")), person(run, item.path("Editor")), modified);
                nodes.save(n);
                map(run, "LIST_ITEM", key, rootUrl + "/" + id, n, modified, null);
            }));
        }
        int next = maxId + 1;
        tx.executeWithoutResult(s -> nodes.findLive(container.getId()).ifPresent(n -> {
            Map<String, Object> settings = new HashMap<>(n.getSettings());
            int current = settings.get("nextItemId") instanceof Number num ? num.intValue() : 1;
            settings.put("nextItemId", Math.max(current, next));
            n.setSettings(settings);
            nodes.save(n);
        }));
        if (attachments > 0) run.warn(attachments + " item(s) in '" + list.path("Title").asText() + "' have attachments, which are not copied");
    }

    // ---- columns and values ----

    /** A SharePoint field and the NEXORA column it maps to. */
    record Field(String spName, String spType, ColumnDef column) {}

    private Map<String, Field> ensureColumns(Run run, String listId, Node container) throws IOException {
        List<JsonNode> spFields = run.sp.all(run.sp.web(run.task.getSourceWeb(), "/_api/web/lists(guid'" + listId + "')/fields?$filter="
                + query("Hidden eq false and ReadOnlyField eq false and FromBaseType eq false")
                + "&$select=InternalName,Title,TypeAsString,Required,Choices"));
        return tx.execute(s -> {
            Map<String, Field> out = new LinkedHashMap<>();
            List<ColumnDef> existing = columns.findByLibraryIdOrderByPosition(container.getId());
            int position = existing.size();
            for (JsonNode f : spFields) {
                String spType = f.path("TypeAsString").asText();
                ColumnDef.Type type = switch (spType) {
                    case "Text" -> ColumnDef.Type.TEXT;
                    case "Note" -> ColumnDef.Type.NOTE;
                    case "Number", "Currency" -> ColumnDef.Type.NUMBER;
                    case "DateTime" -> ColumnDef.Type.DATE;
                    case "Boolean" -> ColumnDef.Type.BOOLEAN;
                    case "Choice", "MultiChoice" -> ColumnDef.Type.CHOICE;
                    case "URL" -> ColumnDef.Type.URL;
                    case "User", "UserMulti" -> ColumnDef.Type.PERSON;
                    default -> null;
                };
                String title = f.path("Title").asText(f.path("InternalName").asText());
                if (type == null) {
                    run.warn("Column '" + title + "' (" + spType + ") in " + container.getPath() + " is not supported and was skipped");
                    continue;
                }
                String internal = Names.columnInternalName(title);
                ColumnDef def = existing.stream().filter(c -> c.getInternalName().equalsIgnoreCase(internal)).findFirst().orElse(null);
                if (def == null) {
                    List<String> choices = new ArrayList<>();
                    f.path("Choices").forEach(c -> choices.add(c.asText()));
                    def = columns.save(new ColumnDef(container.getId(), internal, title, type, false, choices, position++));
                    existing.add(def);
                }
                out.put(f.path("InternalName").asText(), new Field(f.path("InternalName").asText(), spType, def));
            }
            return out;
        });
    }

    private Map<String, Object> values(Run run, JsonNode item, Map<String, Field> fields, Map<String, Object> current) {
        Map<String, Object> out = new HashMap<>(current == null ? Map.of() : current);
        if (item == null || item.isMissingNode()) return out;
        for (Field f : fields.values()) {
            ColumnDef def = f.column();
            String key = def.getInternalName();
            Object value = switch (def.getType()) {
                case PERSON -> {
                    JsonNode id = item.path(f.spName() + "Id");
                    JsonNode first = id.isArray() ? id.path(0) : id;
                    yield first.isNumber() ? personSnapshot(run, first.asInt()) : null;
                }
                case NUMBER -> item.path(f.spName()).isNumber() ? item.path(f.spName()).decimalValue() : null;
                case BOOLEAN -> item.path(f.spName()).isBoolean() ? item.path(f.spName()).asBoolean() : null;
                case URL -> {
                    String u = item.path(f.spName()).path("Url").asText(null);
                    yield u != null && SafeUrls.isSafe(u) ? u : null;
                }
                case CHOICE -> {
                    JsonNode v = item.path(f.spName());
                    String s = v.isArray() ? String.join("; ", iterableText(v)) : v.isNull() || v.isMissingNode() ? null : v.asText();
                    if (s != null && !s.isEmpty() && !def.getChoices().contains(s)) {
                        List<String> choices = new ArrayList<>(def.getChoices());
                        choices.add(s);
                        def.setChoices(choices);
                        columns.save(def);
                    }
                    yield s == null || s.isEmpty() ? null : s;
                }
                case NOTE -> {
                    String s = item.path(f.spName()).isTextual() ? item.path(f.spName()).asText() : null;
                    yield s == null ? null : s.contains("<") ? Jsoup.parse(s).text() : s;   // rich text: keep the text
                }
                default -> item.path(f.spName()).isValueNode() && !item.path(f.spName()).isNull() ? item.path(f.spName()).asText() : null;
            };
            if (value == null) out.remove(key);
            else out.put(key, value);
        }
        return out;
    }

    private static List<String> iterableText(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    // ---- people ----

    /** The NEXORA account for a SharePoint user object (Author, ModifiedBy, CreatedBy), or the migrating admin. */
    private UUID person(Run run, JsonNode user) {
        Principal p = user == null || user.isMissingNode() ? null
                : resolveUser(run, user.path("LoginName").asText(null), user.path("Email").asText(user.path("EMail").asText(null)));
        return p == null ? run.actor.id() : p.getId();
    }

    private Map<String, Object> personSnapshot(Run run, int spUserId) {
        Principal p = run.byUserId.computeIfAbsent(spUserId, id -> {
            try {
                JsonNode u = run.sp.json(run.sp.web(run.task.getSourceWeb(), "/_api/web/getuserbyid(" + id + ")?$select=LoginName,Title,Email,PrincipalType"));
                return u.path("PrincipalType").asInt(1) == 4 ? resolveGroup(run, u.path("LoginName").asText(), u.path("Title").asText())
                        : resolveUser(run, u.path("LoginName").asText(), u.path("Email").asText(null));
            } catch (IOException e) {
                return null;
            }
        });
        return p == null ? null : Map.of("id", p.getId().toString(), "login", p.getLogin(), "displayName", p.getDisplayName());
    }

    Principal resolveUser(Run run, String loginName, String email) {
        if (loginName == null || loginName.isBlank()) return null;
        String n = norm(loginName);
        if (run.users.containsKey(n)) return run.users.get(n);
        List<String> candidates = new ArrayList<>();
        String mapped = run.options.userMappings().get(n);
        if (mapped == null) mapped = run.options.userMappings().get(norm(loginName.toLowerCase(Locale.ROOT)));
        if (mapped != null) candidates.add(mapped);
        candidates.add(n);
        if (n.contains("\\")) candidates.add(n.substring(n.indexOf('\\') + 1));
        if (n.contains("@")) candidates.add(n.substring(0, n.indexOf('@')));
        Principal found = null;
        for (String c : candidates) {
            found = principals.findByKindAndLogin(Principal.Kind.USER, c).orElse(null);
            if (found != null) break;
        }
        if (found == null && email != null && !email.isBlank()) {
            found = principals.search(email).stream().filter(p -> !p.isGroup() && email.equalsIgnoreCase(p.getEmail())).findFirst().orElse(null);
        }
        if (found == null && !n.equalsIgnoreCase("sharepoint\\system") && run.missingUsers.add(n)) {
            run.warn("No NEXORA account for SharePoint user '" + loginName + "': content is attributed to "
                    + run.actor.login() + " and permissions are skipped (add a user mapping to fix)");
        }
        run.users.put(n, found);
        return found;
    }

    Principal resolveGroup(Run run, String loginName, String title) {
        String name = title != null && title.contains("\\") ? title.substring(title.indexOf('\\') + 1) : title;
        String mapped = loginName == null ? null : run.options.userMappings().get(norm(loginName));
        for (String c : new String[] {mapped, name, loginName == null ? null : norm(loginName)}) {
            if (c == null) continue;
            Optional<Principal> g = principals.findByKindAndLogin(Principal.Kind.GROUP, c);
            if (g.isPresent()) return g.get();
        }
        if (run.missingUsers.add("group:" + title)) {
            run.warn("No NEXORA group for the directory group '" + title + "': its permissions are skipped (sync the directory or add a mapping)");
        }
        return null;
    }

    /** SharePoint claims logins ("i:0#.w|contoso\\jdoe") without the claim prefix, lower case. */
    static String norm(String login) {
        String s = login.strip();
        int bar = s.lastIndexOf('|');
        if (bar >= 0) s = s.substring(bar + 1);
        return s.toLowerCase(Locale.ROOT);
    }

    // ---- permissions ----

    private void sitePermissions(Run run) throws IOException {
        String w = run.task.getSourceWeb();
        Map<Integer, String> roles = new HashMap<>();
        for (String[] g : new String[][] {{"associatedownergroup", "owners"}, {"associatedmembergroup", "members"}, {"associatedvisitorgroup", "visitors"}}) {
            try {
                JsonNode group = run.sp.json(run.sp.web(w, "/_api/web/" + g[0] + "?$select=Id,Title"));
                if (group.has("Id")) roles.put(group.path("Id").asInt(), g[1]);
            } catch (SharePointClient.SharePointException e) {
                if (e.status() != 404) throw e;
            }
        }
        run.siteGroupRoles = roles;
        List<JsonNode> assignments = run.sp.all(run.sp.web(w, "/_api/web/roleassignments?$expand=Member,RoleDefinitionBindings"));
        applyAcl(run, run.site, assignments, true);
        run.ctx.log("Site permissions: " + assignments.size() + " role assignments");
    }

    /** Items with their own permissions, read before the list is copied so they are never created open. */
    private List<JsonNode> restrictedItems(Run run, JsonNode list) throws IOException {
        String listId = list.path("Id").asText();
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode item : run.sp.all(run.sp.web(run.task.getSourceWeb(), "/_api/web/lists(guid'" + listId
                + "')/items?$top=2000&$select=Id,FileRef,HasUniqueRoleAssignments"))) {
            if (!item.path("HasUniqueRoleAssignments").asBoolean()) continue;
            out.add(item);
            run.restricted.add(item.path("FileRef").asText("").toLowerCase(Locale.ROOT));
            run.restricted.add((listId + ":" + item.path("Id").asInt(item.path("ID").asInt())).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * Content that has its own permissions in SharePoint starts with an empty ACL (administrators only). The
     * permission step then grants what SharePoint grants, so a failure there leaves it closed, not open.
     */
    private static void lockIfRestricted(Run run, Node node, boolean restricted) {
        if (restricted && run.options.permissions()) node.setInheritsPermissions(false);
    }

    private void listPermissions(Run run, JsonNode list, List<JsonNode> restricted) throws IOException {
        String listId = list.path("Id").asText();
        String w = run.task.getSourceWeb();
        Node container = mapped(run, listId).orElse(null);
        if (container == null) return;
        if (list.path("HasUniqueRoleAssignments").asBoolean()) {
            applyAcl(run, container, run.sp.all(run.sp.web(w, "/_api/web/lists(guid'" + listId + "')/roleassignments?$expand=Member,RoleDefinitionBindings")), false);
        }
        int broken = 0;
        for (JsonNode item : restricted) {
            int id = item.path("Id").asInt(item.path("ID").asInt());
            Node target = LIBRARIES.contains(list.path("BaseTemplate").asInt())
                    ? byUrl(run, item.path("FileRef").asText()) : mapped(run, listId + ":" + id).orElse(null);
            if (target == null) continue;
            applyAcl(run, target, run.sp.all(run.sp.web(w, "/_api/web/lists(guid'" + listId + "')/items(" + id
                    + ")/roleassignments?$expand=Member,RoleDefinitionBindings")), false);
            broken++;
        }
        if (broken > 0) run.ctx.log("  " + broken + " item(s) with their own permissions");
    }

    private void applyAcl(Run run, Node node, List<JsonNode> assignments, boolean site) throws IOException {
        Map<UUID, PermissionLevel> grants = new LinkedHashMap<>();
        for (JsonNode ra : assignments) {
            PermissionLevel level = null;
            for (JsonNode role : ra.path("RoleDefinitionBindings")) level = PermissionLevel.max(level, level(role));
            if (level == null) continue;   // Limited Access only
            Principal p = principalFor(run, ra.path("Member"));
            if (p != null) grants.merge(p.getId(), level, PermissionLevel::max);
        }
        if (grants.isEmpty() && !site) {
            run.warn(node.getPath() + " has its own permissions in SharePoint, but none of those people exist in NEXORA: only administrators can open it");
        }
        tx.executeWithoutResult(s -> {
            Node n = nodes.findLive(node.getId()).orElseThrow();
            if (!site) {
                n.setInheritsPermissions(false);
                nodes.save(n);
                acl.deleteByNodeId(n.getId());
                acl.flush();
            }
            grants.forEach((principal, level) -> {
                AclEntry e = acl.findByNodeIdAndPrincipalId(n.getId(), principal).orElseGet(() -> new AclEntry(n.getId(), principal, level));
                e.setLevel(level);
                acl.save(e);
            });
        });
    }

    /** A role assignment's member: a user, a directory group, or a SharePoint group (copied with its members). */
    private Principal principalFor(Run run, JsonNode member) throws IOException {
        int type = member.path("PrincipalType").asInt();
        if (type == 1) return resolveUser(run, member.path("LoginName").asText(), member.path("Email").asText(null));
        if (type == 4) return resolveGroup(run, member.path("LoginName").asText(), member.path("Title").asText());
        if (type != 8) return null;
        int spId = member.path("Id").asInt();
        if (run.spGroups.containsKey(spId)) return run.spGroups.get(spId);
        String role = run.siteGroupRoles.get(spId);
        String title = member.path("Title").asText("Group " + spId);
        List<JsonNode> users = run.sp.all(run.sp.web(run.task.getSourceWeb(), "/_api/web/sitegroups/getbyid(" + spId
                + ")/users?$select=LoginName,Title,Email,PrincipalType"));
        List<Principal> resolved = new ArrayList<>();
        for (JsonNode u : users) {
            Principal p = u.path("PrincipalType").asInt(1) == 4 ? resolveGroup(run, u.path("LoginName").asText(), u.path("Title").asText())
                    : resolveUser(run, u.path("LoginName").asText(), u.path("Email").asText(null));
            if (p != null) resolved.add(p);
        }
        Principal group = tx.execute(s -> {
            String login = role != null ? run.site.getName() + "-" + role
                    : run.site.getName() + "-" + title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            Principal g = principals.findByKindAndLogin(Principal.Kind.GROUP, login)
                    .orElseGet(() -> principals.save(Principal.group(login, title, run.site.getId())));
            for (Principal p : resolved) {
                if (!members.existsByGroupIdAndMemberId(g.getId(), p.getId())) members.save(new GroupMember(g.getId(), p.getId()));
            }
            return g;
        });
        run.spGroups.put(spId, group);
        return group;
    }

    static PermissionLevel level(JsonNode role) {
        int kind = role.path("RoleTypeKind").asInt(-1);
        switch (kind) {
            case 5: return PermissionLevel.FULL_CONTROL;
            case 4, 6: return PermissionLevel.EDIT;
            case 3: return PermissionLevel.CONTRIBUTE;
            case 2: return PermissionLevel.READ;
            case 1: return null;
            default: break;
        }
        String name = role.path("Name").asText("").toLowerCase(Locale.ROOT);
        if (name.contains("full control")) return PermissionLevel.FULL_CONTROL;
        if (name.contains("design") || name.equals("edit")) return PermissionLevel.EDIT;
        if (name.contains("contribute") || name.contains("approve")) return PermissionLevel.CONTRIBUTE;
        if (name.contains("read") || name.contains("view")) return PermissionLevel.READ;
        return null;
    }

    // ---- delta removal ----

    private void deleteMissing(Run run) {
        for (MigrationItem i : items.findByTaskId(run.task.getId())) {
            if (!Set.of("FILE", "FOLDER", "LIST_ITEM").contains(i.getKind()) || run.seen.contains(i.getSourceKey())) continue;
            nodes.findLive(i.getNodeId()).ifPresent(n -> {
                nodes.softDeleteSubtree(n.getId(), NodeRepository.likePrefix(n.getPath()), Instant.now(), run.actor.id());
                run.deleted++;
            });
            items.delete(i);
        }
        if (run.deleted > 0) run.ctx.log("Moved " + run.deleted + " item(s) deleted in SharePoint to the recycle bin");
    }

    // ---- bookkeeping ----

    private void map(Run run, String kind, String key, String url, Node node, Instant modified, String version) {
        MigrationItem i = items.findByTaskIdAndSourceKey(run.task.getId(), key)
                .orElseGet(() -> new MigrationItem(run.task.getId(), kind, key, url, node.getId()));
        i.seen(url, node.getId(), modified, version);
        items.save(i);
    }

    private Optional<Node> mapped(Run run, String key) {
        return items.findByTaskIdAndSourceKey(run.task.getId(), key).flatMap(i -> nodes.findLive(i.getNodeId()));
    }

    private Node byUrl(Run run, String url) {
        Node cached = run.listItemIndex.get(url.toLowerCase(Locale.ROOT));
        if (cached != null) return cached;
        return items.findBySourceUrl(url).stream().filter(i -> i.getTaskId().equals(run.task.getId())).findFirst()
                .flatMap(i -> nodes.findLive(i.getNodeId())).orElse(null);
    }

    /** "_vti_history/512/Shared Documents/a.docx" or an absolute server-relative URL, relative to the web. */
    private static String relativeToWeb(Run run, String url) {
        String web = run.task.getSourceWeb().equals("/") ? "" : run.task.getSourceWeb();
        if (url.startsWith("/") && !web.isEmpty() && url.toLowerCase(Locale.ROOT).startsWith(web.toLowerCase(Locale.ROOT) + "/")) {
            return url.substring(web.length() + 1);
        }
        return url.startsWith("/") ? url.substring(1) : url;
    }

    static int[] parseLabel(String label) {
        if (label == null || label.isBlank()) return new int[] {0, 0};
        String[] p = label.strip().split("\\.");
        try {
            return new int[] {Integer.parseInt(p[0]), p.length > 1 ? Integer.parseInt(p[1]) : 0};
        } catch (NumberFormatException e) {
            return new int[] {0, 0};
        }
    }

    static int compare(int[] a, int[] b) {
        return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
    }

    private static Instant instant(JsonNode n) {
        String s = n == null ? null : n.asText(null);
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            try {
                return java.time.OffsetDateTime.parse(s).toInstant();
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    @FunctionalInterface
    interface IoWork {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface IoValue<T> {
        T get() throws IOException;
    }

    /** The state of one copy run. */
    final class Run {
        final SharePointClient sp;
        final MigrationTask task;
        final Options options;
        final CurrentUser actor;
        final JobService.Context ctx;
        Node site;
        Map<Integer, String> siteGroupRoles = Map.of();
        final Map<Integer, Principal> spGroups = new HashMap<>();
        final Map<Integer, Principal> byUserId = new HashMap<>();
        final Map<String, Principal> users = new HashMap<>();
        final Set<String> missingUsers = new HashSet<>();
        final Set<String> seen = new HashSet<>();
        final Set<String> restricted = new HashSet<>();
        final Map<String, Node> listItemIndex = new HashMap<>();
        int created, updated, unchanged, versions, deleted, failed, warnings;
        long bytes;

        Run(SharePointClient sp, MigrationTask task, Options options, CurrentUser actor, JobService.Context ctx) {
            this.sp = sp;
            this.task = task;
            this.options = options;
            this.actor = actor;
            this.ctx = ctx;
        }

        void warn(String text) {
            warnings++;
            ctx.log("Warning: " + text);
        }

        /** Runs one step; a failure is logged and counted, and the copy goes on with the next item. */
        void guarded(String what, IoWork work) {
            guardedValue(what, () -> {
                work.run();
                return Boolean.TRUE;
            });
        }

        <T> T guardedValue(String what, IoValue<T> work) {
            try {
                return work.get();
            } catch (JobService.CancelledException e) {
                throw e;
            } catch (Exception e) {
                failed++;
                ctx.log("Failed: " + what + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                return null;
            }
        }
    }
}
