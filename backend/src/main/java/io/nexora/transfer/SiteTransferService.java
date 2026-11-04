package io.nexora.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.nexora.acl.AclEntry;
import io.nexora.acl.AclEntryRepository;
import io.nexora.acl.PermissionLevel;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.lists.ItemVersion;
import io.nexora.workflow.Approval;
import io.nexora.workflow.ApprovalRepository;
import io.nexora.workflow.ApprovalStep;
import io.nexora.workflow.ApprovalStepRepository;
import io.nexora.workflow.WorkflowRule;
import io.nexora.workflow.WorkflowRuleRepository;
import io.nexora.lists.ItemVersionRepository;
import io.nexora.content.*;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.GroupMember;
import io.nexora.identity.GroupMemberRepository;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.pages.SitePage;
import io.nexora.pages.SitePageRepository;
import io.nexora.pages.SitePageVersion;
import io.nexora.pages.SitePageVersionRepository;
import io.nexora.portal.SiteAsset;
import io.nexora.portal.SiteAssetRepository;
import io.nexora.storage.BlobStore;
import io.nexora.storage.StoredBlob;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Writes a site to a {@link SitePackage} and reads one back as a new site: the engine behind site and farm
 * backups, restores and moving sites between farms. Callers check that the actor is a farm administrator.
 */
@Service
public class SiteTransferService {
    private static final String HOME_SETTING = "homePageId";
    private static final List<String> SITE_ROLES = List.of("owners", "members", "visitors");

    private final NodeRepository nodes;
    private final FileVersionRepository versions;
    private final ColumnDefRepository columns;
    private final AclEntryRepository acl;
    private final PrincipalRepository principals;
    private final GroupMemberRepository members;
    private final SitePageRepository pages;
    private final SitePageVersionRepository pageVersions;
    private final SiteAssetRepository assets;
    private final ContentService content;
    private final BlobStore store;
    private final AuditService audit;
    private final ItemVersionRepository itemVersions;
    private final ApprovalRepository approvals;
    private final ApprovalStepRepository approvalSteps;
    private final WorkflowRuleRepository rules;
    private final ObjectMapper json;

    public SiteTransferService(NodeRepository nodes, FileVersionRepository versions, ColumnDefRepository columns,
                               AclEntryRepository acl, PrincipalRepository principals, GroupMemberRepository members,
                               SitePageRepository pages, SitePageVersionRepository pageVersions,
                               SiteAssetRepository assets, ContentService content, BlobStore store,
                               AuditService audit, ItemVersionRepository itemVersions, ApprovalRepository approvals,
                               ApprovalStepRepository approvalSteps, WorkflowRuleRepository rules, ObjectMapper json) {
        this.approvals = approvals;
        this.approvalSteps = approvalSteps;
        this.rules = rules;
        this.itemVersions = itemVersions;
        this.nodes = nodes;
        this.versions = versions;
        this.columns = columns;
        this.acl = acl;
        this.principals = principals;
        this.members = members;
        this.pages = pages;
        this.pageVersions = pageVersions;
        this.assets = assets;
        this.content = content;
        this.store = store;
        this.audit = audit;
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public record ExportResult(String siteName, String title, int nodes, int versions, long bytes) {}

    public record ImportResult(UUID siteId, String path, int nodes, int versions, int pages, List<String> warnings) {}

    // ---- export --------------------------------------------------------------------------------

    /** Writes the live content of a site (not its recycle bin) to {@code target}. */
    @Transactional(readOnly = true)
    public ExportResult export(UUID siteId, String exportedBy, Path target, Consumer<String> log) throws IOException {
        Node site = nodes.findLive(siteId).filter(n -> n.getKind() == Node.Kind.SITE)
                .orElseThrow(() -> ApiException.notFound("Site"));
        List<Node> tree = new ArrayList<>(nodes.findLiveDescendants(NodeRepository.likePrefix(site.getPath())));
        tree.sort(Comparator.comparingInt((Node n) -> depth(n.getPath())).thenComparing(Node::getPath));
        List<UUID> ids = tree.stream().map(Node::getId).toList();

        Map<UUID, List<FileVersion>> history = new HashMap<>();
        for (List<UUID> chunk : chunks(ids)) {
            for (FileVersion v : versions.findByNodeIdIn(chunk)) history.computeIfAbsent(v.getNodeId(), k -> new ArrayList<>()).add(v);
        }

        // people: everyone named as author, in an ACL, or in one of the site's groups
        Set<UUID> people = new HashSet<>();
        people.add(site.getCreatedBy());
        tree.forEach(n -> { people.add(n.getCreatedBy()); people.add(n.getModifiedBy()); });
        history.values().forEach(l -> l.forEach(v -> people.add(v.getCreatedBy())));
        Map<UUID, List<ItemVersion>> itemHistory = new HashMap<>();
        for (List<UUID> chunk : chunks(ids)) {
            for (ItemVersion v : itemVersions.findByItemIdIn(chunk)) {
                itemHistory.computeIfAbsent(v.getItemId(), k -> new ArrayList<>()).add(v);
                people.add(v.getCreatedBy());
            }
        }
        List<AclEntry> entries = new ArrayList<>(acl.findByNodeId(site.getId()));
        for (Node n : tree) if (!n.isInheritsPermissions()) entries.addAll(acl.findByNodeId(n.getId()));
        entries.forEach(e -> people.add(e.getPrincipalId()));
        List<Principal> siteGroups = principals.findBySiteId(site.getId());
        Map<UUID, List<UUID>> groupMembers = new HashMap<>();
        for (Principal g : siteGroups) {
            people.add(g.getId());
            List<UUID> m = members.findByGroupId(g.getId()).stream().map(GroupMember::getMemberId).toList();
            groupMembers.put(g.getId(), m);
            people.addAll(m);
        }
        List<SitePage> sitePages = pages.findBySiteId(site.getId());
        Map<UUID, List<SitePageVersion>> pageHistory = new HashMap<>();
        for (SitePage p : sitePages) {
            people.add(p.getCreatedBy());
            people.add(p.getModifiedBy());
            if (p.getPublishedBy() != null) people.add(p.getPublishedBy());
            List<SitePageVersion> pv = pageVersions.findByPageIdOrderByVersionDesc(p.getId());
            pv.forEach(v -> people.add(v.getPublishedBy()));
            pageHistory.put(p.getId(), pv);
        }
        Map<UUID, List<Approval>> approvalHistory = new HashMap<>();
        Map<UUID, List<ApprovalStep>> stepHistory = new HashMap<>();
        for (List<UUID> chunk : chunks(ids)) {
            for (Approval a : approvals.findByNodeIdIn(chunk)) {
                approvalHistory.computeIfAbsent(a.getNodeId(), k -> new ArrayList<>()).add(a);
                people.add(a.getRequestedBy());
                List<ApprovalStep> steps = approvalSteps.findByApprovalIdOrderByPosition(a.getId());
                stepHistory.put(a.getId(), steps);
                steps.forEach(s -> { people.add(s.getPrincipalId()); people.add(s.getDecidedBy()); });
            }
        }
        people.remove(null);
        Map<UUID, Principal> byId = new HashMap<>();
        principals.findAllById(people).forEach(p -> byId.put(p.getId(), p));
        java.util.function.Function<UUID, SitePackage.PrincipalRef> ref = id -> ref(byId.get(id), site.getId());

        List<SitePackage.NodeRec> nodeRecs = new ArrayList<>();
        List<SitePackage.ColumnRec> columnRecs = new ArrayList<>();
        int versionCount = 0;
        for (Node n : tree) {
            List<SitePackage.VersionRec> vr = new ArrayList<>();
            List<FileVersion> h = new ArrayList<>(history.getOrDefault(n.getId(), List.of()));
            h.sort(Comparator.comparingInt(FileVersion::getMajor).thenComparingInt(FileVersion::getMinor));
            for (FileVersion v : h) {
                vr.add(new SitePackage.VersionRec(v.getMajor(), v.getMinor(), v.getSha256(), v.getSizeBytes(),
                        v.getMimeType(), v.getFileName(), v.getComment(), ref.apply(v.getCreatedBy()), v.getCreatedAt()));
            }
            versionCount += vr.size();
            nodeRecs.add(new SitePackage.NodeRec(n.getId(), n.getParentId(), n.getKind().name(), n.getName(), n.getTitle(),
                    n.getDescription(), n.getMetadata(), n.getSettings(), n.isInheritsPermissions(),
                    ref.apply(n.getCreatedBy()), n.getCreatedAt(), ref.apply(n.getModifiedBy()), n.getModifiedAt(), vr,
                    itemHistory.getOrDefault(n.getId(), List.of()).stream().map(v -> new SitePackage.ItemVersionRec(v.getVersion(), v.getTitle(),
                            v.getMetadata(), ref.apply(v.getCreatedBy()), v.getCreatedAt())).toList()));
            if (n.getKind().hasColumns()) {
                for (ColumnDef c : columns.findByLibraryIdOrderByPosition(n.getId())) {
                    columnRecs.add(new SitePackage.ColumnRec(n.getId(), c.getInternalName(), c.getDisplayName(),
                            c.getType().name(), c.isRequired(), c.getChoices(), c.getPosition(), c.getLookupListId()));
                }
            }
        }
        List<SitePackage.AclRec> aclRecs = new ArrayList<>();
        for (AclEntry e : entries) {
            SitePackage.PrincipalRef p = ref.apply(e.getPrincipalId());
            if (p != null) aclRecs.add(new SitePackage.AclRec(e.getNodeId(), p, e.getLevel().name()));
        }
        List<SitePackage.GroupRec> groupRecs = new ArrayList<>();
        for (Principal g : siteGroups) {
            groupRecs.add(new SitePackage.GroupRec(ref.apply(g.getId()),
                    groupMembers.get(g.getId()).stream().map(ref).filter(Objects::nonNull).toList()));
        }
        String home = Objects.toString(site.getSettings().get(HOME_SETTING), null);
        List<SitePackage.PageRec> pageRecs = new ArrayList<>();
        for (SitePage p : sitePages) {
            List<SitePackage.PageVersionRec> pv = pageHistory.get(p.getId()).stream()
                    .map(v -> new SitePackage.PageVersionRec(v.getVersion(), v.getTitle(), v.getDescription(),
                            v.getBannerImageId(), v.getCanvas(), ref.apply(v.getPublishedBy()), v.getPublishedAt()))
                    .toList();
            pageRecs.add(new SitePackage.PageRec(p.getName(), p.getKind().name(), p.getTitle(), p.getDescription(),
                    p.getBannerImageId(), p.getCanvas(), p.getDraftTitle(), p.getDraftDescription(), p.getDraftBannerId(),
                    p.getDraftCanvas(), p.getPublishedVersion(), ref.apply(p.getCreatedBy()), p.getCreatedAt(),
                    ref.apply(p.getModifiedBy()), p.getModifiedAt(), ref.apply(p.getPublishedBy()), p.getPublishedAt(),
                    p.getId().toString().equals(home), pv));
        }
        Optional<SiteAsset> logo = assets.findBySiteIdAndKind(site.getId(), "logo");
        Map<String, Object> siteSettings = new HashMap<>(site.getSettings());
        siteSettings.remove(HOME_SETTING);

        List<SitePackage.RuleRec> ruleRecs = new ArrayList<>();
        for (Node n : tree) {
            if (!n.getKind().hasColumns()) continue;
            for (WorkflowRule r : rules.findByContainerIdOrderByCreatedAt(n.getId())) {
                ruleRecs.add(new SitePackage.RuleRec(n.getId(), r.getName(), r.isEnabled(), r.getDefinition()));
            }
        }
        List<SitePackage.ApprovalRec> approvalRecs = new ArrayList<>();
        approvalHistory.values().forEach(list -> list.forEach(a -> approvalRecs.add(new SitePackage.ApprovalRec(a.getNodeId(), a.getKind().name(),
                a.getMode().name(), a.getTitle(), a.getMessage(), a.getStatus().name(), a.getOutcome(), ref.apply(a.getRequestedBy()), a.getRequestedByName(),
                a.getVersionLabel(), a.getCreatedAt(), a.getDueAt(), a.getCompletedAt(), stepHistory.get(a.getId()).stream().map(s -> new SitePackage.StepRec(
                        s.getPosition(), ref.apply(s.getPrincipalId()), s.getPrincipalName(), s.getDecision().name(), ref.apply(s.getDecidedBy()), s.getDecidedByName(),
                        s.getDecidedAt(), s.getComment())).toList()))));

        SitePackage pkg = new SitePackage(SitePackage.FORMAT, SitePackage.VERSION, Instant.now(), exportedBy,
                new SitePackage.SiteRec(site.getId(), site.getName(), site.getTitle(), site.getDescription(), siteSettings,
                        ref.apply(site.getCreatedBy()), site.getCreatedAt()),
                groupRecs, nodeRecs, columnRecs, aclRecs, pageRecs,
                logo.map(l -> new SitePackage.LogoRec(l.getMimeType(), l.getSha256())).orElse(null), ruleRecs, approvalRecs);

        Files.createDirectories(target.toAbsolutePath().getParent());
        Set<String> written = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
            zip.putNextEntry(new ZipEntry(SitePackage.MANIFEST));
            zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(pkg));
            zip.closeEntry();
            int done = 0;
            for (List<FileVersion> h : history.values()) {
                for (FileVersion v : h) {
                    if (!written.add(v.getSha256())) continue;
                    zip.putNextEntry(new ZipEntry(SitePackage.blobEntry(v.getSha256())));
                    try (InputStream in = store.open(v.getStorageKey())) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                    if (++done % 200 == 0) log.accept("Exported " + done + " files of " + site.getPath());
                }
            }
            if (logo.isPresent() && written.add(logo.get().getSha256())) {
                zip.putNextEntry(new ZipEntry(SitePackage.blobEntry(logo.get().getSha256())));
                zip.write(logo.get().getData());
                zip.closeEntry();
            }
        }
        log.accept("Exported " + site.getPath() + ": " + tree.size() + " items, " + versionCount + " versions, "
                + pageRecs.size() + " pages");
        return new ExportResult(site.getName(), site.getTitle(), tree.size(), versionCount, Files.size(target));
    }

    /** Reads just the manifest, e.g. to show what a backup contains. */
    public SitePackage readManifest(Path zipPath) throws IOException {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            return manifest(zip);
        }
    }

    // ---- import --------------------------------------------------------------------------------

    /**
     * Creates a new site at {@code /sites/<targetName>} from a package. People are matched by login (users
     * also by e-mail); content by unknown people is attributed to the importing administrator, and their
     * permissions are skipped with a warning.
     */
    @Transactional
    public ImportResult importSite(Path zipPath, String targetName, String titleOverride, CurrentUser actor,
                                   Consumer<String> log) throws IOException {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            SitePackage pkg = manifest(zip);
            String name = targetName == null || targetName.isBlank() ? pkg.site().name() : targetName;
            String title = titleOverride == null || titleOverride.isBlank() ? pkg.site().title() : titleOverride;
            Node site = content.createSite(actor, name, title, pkg.site().description());
            Resolver people = new Resolver(site, actor);

            // the site's groups: same members as in the package
            for (SitePackage.GroupRec g : pkg.groups()) {
                Principal group = people.principal(g.group());
                if (group == null) continue;
                if (g.group().siteRole() != null) {
                    members.findByGroupId(group.getId()).forEach(members::delete);
                    members.flush();
                }
                for (SitePackage.PrincipalRef m : g.members()) {
                    Principal p = people.principal(m);
                    if (p != null && !p.getId().equals(group.getId())
                            && !members.existsByGroupIdAndMemberId(group.getId(), p.getId())) {
                        members.save(new GroupMember(group.getId(), p.getId()));
                    }
                }
            }

            Map<UUID, Node> created = new HashMap<>();
            UUID oldSiteId = pkg.site().id();
            created.put(oldSiteId, site);
            Map<String, StoredBlob> blobs = new HashMap<>();
            int versionCount = 0;
            int count = 0;
            for (SitePackage.NodeRec r : pkg.nodes()) {
                Node parent = created.get(r.parentId());
                if (parent == null) {
                    people.warn("Skipped '" + r.name() + "': its folder is missing from the package");
                    continue;
                }
                Node.Kind kind = Node.Kind.valueOf(r.kind());
                Node n = parent == site && kind == Node.Kind.LIBRARY
                        ? nodes.findLiveByPath(site.getPath() + "/" + r.name()).orElse(null) : null;
                if (n == null) n = Node.create(kind, parent, r.name(), actor.id());
                n.setTitle(r.title());
                n.setDescription(r.description());
                n.setMetadata(people.remapPeople(r.metadata()));
                n.setSettings(r.settings() == null ? new HashMap<>() : new HashMap<>(r.settings()));
                n.setInheritsPermissions(r.inheritsPermissions());
                n.importAudit(people.id(r.createdBy()), r.createdAt(), people.id(r.modifiedBy()), r.modifiedAt());
                nodes.saveAndFlush(n);
                created.put(r.id(), n);

                if (kind == Node.Kind.FILE && !r.versions().isEmpty()) {
                    FileVersion last = null;
                    for (SitePackage.VersionRec v : r.versions()) {
                        StoredBlob b = blob(zip, v.sha256(), blobs);
                        last = versions.save(FileVersion.imported(n.getId(), v.major(), v.minor(), b.key(), b.sizeBytes(),
                                b.sha256(), v.mimeType(), v.fileName() == null ? r.name() : v.fileName(), v.comment(),
                                people.id(v.createdBy()), v.createdAt()));
                        versionCount++;
                    }
                    n.setCurrentVersionId(last.getId());
                    n.setSizeBytes(last.getSizeBytes());
                    n.setMimeType(last.getMimeType());
                    nodes.save(n);
                }
                if (r.itemVersions() != null) {
                    for (SitePackage.ItemVersionRec v : r.itemVersions()) {
                        itemVersions.save(new ItemVersion(n.getId(), v.version(), v.title(), people.remapPeople(v.metadata()),
                                people.id(v.createdBy()), v.createdAt()));
                    }
                }
                if (++count % 500 == 0) log.accept("Restored " + count + " of " + pkg.nodes().size() + " items");
            }

            for (SitePackage.ColumnRec c : pkg.columns()) {
                Node container = created.get(c.containerId());
                if (container == null) continue;
                ColumnDef column = new ColumnDef(container.getId(), c.internalName(), c.displayName(),
                        ColumnDef.Type.valueOf(c.type()), c.required(), c.choices(), c.position());
                Node lookupList = c.lookupListId() == null ? null : created.get(c.lookupListId());
                if (lookupList != null) column.setLookupListId(lookupList.getId());
                columns.save(column);
            }

            // permissions: the package's entries replace the defaults on the site and every broken node
            Map<UUID, List<SitePackage.AclRec>> aclByNode = new HashMap<>();
            pkg.acl().forEach(a -> aclByNode.computeIfAbsent(a.nodeId(), k -> new ArrayList<>()).add(a));
            for (Map.Entry<UUID, List<SitePackage.AclRec>> e : aclByNode.entrySet()) {
                Node n = created.get(e.getKey());
                if (n == null || (n != site && n.isInheritsPermissions())) continue;
                List<AclEntry> grants = new ArrayList<>();
                for (SitePackage.AclRec a : e.getValue()) {
                    Principal p = people.principal(a.principal());
                    if (p != null) grants.add(new AclEntry(n.getId(), p.getId(), PermissionLevel.valueOf(a.level())));
                }
                if (n == site && grants.isEmpty()) continue;   // keep the default site permissions
                acl.deleteByNodeId(n.getId());
                acl.flush();
                grants.forEach(acl::save);
            }

            // pages, with web parts and banners pointing at the new ids
            Map<String, String> idMap = new HashMap<>();
            created.forEach((oldId, n) -> idMap.put(oldId.toString(), n.getId().toString()));
            UUID homePage = null;
            for (SitePackage.PageRec p : pkg.pages()) {
                SitePage page = SitePage.restored(site.getId(), p.name(), SitePage.Kind.valueOf(p.kind()), p.title(),
                        p.description(), remapId(p.bannerImageId(), idMap), remapCanvas(p.canvas(), idMap),
                        p.draftTitle(), p.draftDescription(), remapId(p.draftBannerId(), idMap),
                        remapCanvas(p.draftCanvas(), idMap), p.publishedVersion(), people.id(p.createdBy()),
                        p.createdAt(), people.id(p.modifiedBy()), p.modifiedAt(), people.idOrNull(p.publishedBy()),
                        p.publishedAt());
                pages.save(page);
                for (SitePackage.PageVersionRec v : p.versions()) {
                    pageVersions.save(SitePageVersion.restored(page.getId(), v.version(), v.title(), v.description(),
                            remapId(v.bannerImageId(), idMap), remapCanvas(v.canvas(), idMap), people.id(v.publishedBy()),
                            v.publishedAt()));
                }
                if (p.home()) homePage = page.getId();
            }

            Map<String, Object> settings = new HashMap<>(site.getSettings());
            if (pkg.site().settings() != null) settings.putAll(pkg.site().settings());
            if (homePage != null) settings.put(HOME_SETTING, homePage.toString());
            site.setSettings(settings);
            site.importAudit(people.id(pkg.site().createdBy()), pkg.site().createdAt(), actor.id(), Instant.now());
            nodes.save(site);

            if (pkg.logo() != null && zip.getEntry(SitePackage.blobEntry(pkg.logo().sha256())) != null) {
                try (InputStream in = zip.getInputStream(zip.getEntry(SitePackage.blobEntry(pkg.logo().sha256())))) {
                    SiteAsset logo = new SiteAsset(site.getId(), "logo");
                    logo.replace(pkg.logo().mimeType(), pkg.logo().sha256(), in.readAllBytes());
                    assets.save(logo);
                }
            }

            audit.record(actor, "SITE_RESTORE", site, Map.of("from", pkg.site().name(), "items", count));
            people.warnings().forEach(w -> log.accept("Warning: " + w));
            log.accept("Restored " + site.getPath() + ": " + count + " items, " + versionCount + " versions, "
                    + pkg.pages().size() + " pages");
            if (pkg.rules() != null) {
                for (SitePackage.RuleRec r : pkg.rules()) {
                    Node container = created.get(r.containerId());
                    if (container != null) rules.save(new WorkflowRule(container.getId(), r.name(), r.enabled(), r.definition(), actor.id()));
                }
            }
            if (pkg.approvals() != null) {
                for (SitePackage.ApprovalRec a : pkg.approvals()) {
                    Node node = created.get(a.nodeId());
                    if (node == null) continue;
                    // nobody is waiting on a copy: a request that was still open stays in the record as cancelled
                    boolean open = Approval.Status.valueOf(a.status()) == Approval.Status.PENDING;
                    Approval saved = approvals.save(Approval.restored(node.getId(), Approval.Kind.valueOf(a.kind()), Approval.Mode.valueOf(a.mode()), a.title(),
                            a.message(), open ? Approval.Status.CANCELLED : Approval.Status.valueOf(a.status()), open ? "Cancelled when the site was restored" : a.outcome(),
                            a.requestedBy() == null ? null : people.id(a.requestedBy()), a.requestedByName(), a.versionLabel(), a.createdAt(), a.dueAt(),
                            open ? Instant.now() : a.completedAt()));
                    for (SitePackage.StepRec s : a.steps()) {
                        ApprovalStep.Decision decision = ApprovalStep.Decision.valueOf(s.decision());
                        approvalSteps.save(ApprovalStep.restored(saved.getId(), s.position(), s.principal() == null ? null : people.id(s.principal()), s.principalName(),
                                decision == ApprovalStep.Decision.PENDING ? ApprovalStep.Decision.SKIPPED : decision,
                                s.decidedBy() == null ? null : people.id(s.decidedBy()), s.decidedByName(), s.decidedAt(), s.comment()));
                    }
                }
            }
            return new ImportResult(site.getId(), site.getPath(), count, versionCount, pkg.pages().size(), people.warnings());
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private SitePackage manifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(SitePackage.MANIFEST);
        if (entry == null) throw ApiException.badRequest("invalid_package", "This is not a NEXORA site package (no manifest)");
        SitePackage pkg;
        try (InputStream in = zip.getInputStream(entry)) {
            pkg = json.readValue(in, SitePackage.class);
        }
        if (!SitePackage.FORMAT.equals(pkg.format())) throw ApiException.badRequest("invalid_package", "Unknown package format");
        if (pkg.formatVersion() > SitePackage.VERSION) {
            throw ApiException.badRequest("invalid_package", "The package was made by a newer NEXORA version");
        }
        return pkg;
    }

    /** Stores a blob from the package once, verifying its checksum. */
    private StoredBlob blob(ZipFile zip, String sha256, Map<String, StoredBlob> cache) throws IOException {
        StoredBlob known = cache.get(sha256);
        if (known != null) return known;
        ZipEntry e = zip.getEntry(SitePackage.blobEntry(sha256));
        if (e == null) throw ApiException.badRequest("invalid_package", "The package is missing the content " + sha256);
        Path tmp = Files.createTempFile("nexora-restore", ".blob");
        try {
            try (InputStream in = zip.getInputStream(e)) {
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            StoredBlob b = store.put(tmp);
            if (!b.sha256().equalsIgnoreCase(sha256)) {
                throw ApiException.badRequest("invalid_package", "The content " + sha256 + " is damaged (checksum mismatch)");
            }
            cache.put(sha256, b);
            return b;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static UUID remapId(UUID id, Map<String, String> map) {
        if (id == null) return null;
        String m = map.get(id.toString());
        return m == null ? null : UUID.fromString(m);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> remapCanvas(Map<String, Object> canvas, Map<String, String> map) {
        if (canvas == null) return null;
        return (Map<String, Object>) remapValue(canvas, map);
    }

    private static Object remapValue(Object v, Map<String, String> map) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, val) -> {
                String key = String.valueOf(k);
                if ((key.equals("fileId") || key.equals("libraryId") || key.equals("listId")) && val instanceof String s) {
                    out.put(key, map.get(s));   // content that did not come along is dropped from the web part
                } else {
                    out.put(key, remapValue(val, map));
                }
            });
            return out;
        }
        if (v instanceof List<?> l) return l.stream().map(x -> remapValue(x, map)).toList();
        return v;
    }

    private static SitePackage.PrincipalRef ref(Principal p, UUID siteId) {
        if (p == null) return null;
        String role = null;
        if (p.isGroup() && siteId.equals(p.getSiteId())) {
            for (String r : SITE_ROLES) if (p.getLogin().toLowerCase(Locale.ROOT).endsWith("-" + r)) role = r;
        }
        return new SitePackage.PrincipalRef(p.getKind().name(), p.getLogin(), p.getDisplayName(), p.getEmail(), role);
    }

    private static int depth(String path) {
        return (int) path.chars().filter(c -> c == '/').count();
    }

    private static <T> List<List<T>> chunks(List<T> all) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += 500) out.add(all.subList(i, Math.min(all.size(), i + 500)));
        return out;
    }

    /** Matches package principals to this farm's accounts and collects warnings. */
    private final class Resolver {
        private final Node site;
        private final CurrentUser actor;
        private final Map<String, Principal> cache = new HashMap<>();
        private final Set<String> missing = new LinkedHashSet<>();
        private final List<String> warnings = new ArrayList<>();

        Resolver(Node site, CurrentUser actor) {
            this.site = site;
            this.actor = actor;
        }

        Principal principal(SitePackage.PrincipalRef r) {
            if (r == null) return null;
            String key = r.kind() + ":" + r.login() + ":" + r.siteRole();
            if (cache.containsKey(key)) return cache.get(key);
            Principal p;
            if (r.siteRole() != null) {
                p = principals.findByKindAndLogin(Principal.Kind.GROUP, site.getName() + "-" + r.siteRole()).orElse(null);
            } else if ("GROUP".equals(r.kind())) {
                p = principals.findByKindAndLogin(Principal.Kind.GROUP, r.login()).orElseGet(() -> {
                    // a farm group the target does not have yet: create it (directory sync will fill it)
                    Principal g = principals.save(Principal.group(r.login(), r.displayName() == null ? r.login() : r.displayName(), null));
                    warnings.add("Created the group '" + r.login() + "'");
                    return g;
                });
            } else {
                p = principals.findByKindAndLogin(Principal.Kind.USER, r.login())
                        .or(() -> r.email() == null ? Optional.empty() : principals.search(r.email()).stream()
                                .filter(x -> !x.isGroup() && r.email().equalsIgnoreCase(x.getEmail())).findFirst())
                        .orElse(null);
            }
            if (p == null && missing.add(r.login())) {
                warnings.add("Unknown user '" + r.login() + "': their content is attributed to " + actor.login()
                        + " and their permissions were skipped");
            }
            cache.put(key, p);
            return p;
        }

        UUID id(SitePackage.PrincipalRef r) {
            Principal p = principal(r);
            return p == null ? actor.id() : p.getId();
        }

        UUID idOrNull(SitePackage.PrincipalRef r) {
            return r == null ? null : id(r);
        }

        void warn(String w) {
            warnings.add(w);
        }

        List<String> warnings() {
            return warnings;
        }

        /** Person column values carry a snapshot with the source farm's id: point them at this farm's account. */
        Map<String, Object> remapPeople(Map<String, Object> metadata) {
            if (metadata == null) return new HashMap<>();
            Map<String, Object> out = new HashMap<>();
            metadata.forEach((k, v) -> {
                if (v instanceof Map<?, ?> m && m.get("login") != null && m.get("displayName") != null) {
                    String login = m.get("login").toString();
                    Principal p = principals.findByKindAndLogin(Principal.Kind.USER, login)
                            .or(() -> principals.findByKindAndLogin(Principal.Kind.GROUP, login)).orElse(null);
                    if (p != null) out.put(k, Map.of("id", p.getId().toString(), "login", p.getLogin(), "displayName", p.getDisplayName()));
                } else {
                    out.put(k, v);
                }
            });
            return out;
        }
    }
}
