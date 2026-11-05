package io.nexora.content;

import io.nexora.acl.PermissionLevel;
import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.GroupMember;
import io.nexora.identity.GroupMemberRepository;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.lists.ItemHistory;
import io.nexora.lists.ItemVersion;
import io.nexora.lists.ItemVersionRepository;
import io.nexora.search.SearchQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Sites, libraries, folders, rename/move/delete, recycle bin, metadata and columns. */
@Service
@Transactional
public class ContentService {
    public static final String DEFAULT_LIBRARY = "Shared Documents";

    private final NodeRepository nodes;
    private final FileVersionRepository versions;
    private final ColumnDefRepository columns;
    private final PrincipalRepository principals;
    private final GroupMemberRepository members;
    private final PermissionService permissions;
    private final AuditService audit;
    private final BlobGarbageCollector gc;
    private final EditLockService editLocks;
    private final SearchQueue search;
    private final ItemHistory itemHistory;
    private final ItemVersionRepository itemVersions;
    private final QuotaService quotas;
    private final boolean selfServiceSites;

    public ContentService(NodeRepository nodes, FileVersionRepository versions, ColumnDefRepository columns,
                          PrincipalRepository principals, GroupMemberRepository members,
                          PermissionService permissions, AuditService audit, BlobGarbageCollector gc,
                          EditLockService editLocks, SearchQueue search, ItemHistory itemHistory,
                          ItemVersionRepository itemVersions, QuotaService quotas,
                          @Value("${nexora.sites.self-service:true}") boolean selfServiceSites) {
        this.quotas = quotas;
        this.editLocks = editLocks;
        this.search = search;
        this.itemHistory = itemHistory;
        this.itemVersions = itemVersions;
        this.nodes = nodes;
        this.versions = versions;
        this.columns = columns;
        this.principals = principals;
        this.members = members;
        this.permissions = permissions;
        this.audit = audit;
        this.gc = gc;
        this.selfServiceSites = selfServiceSites;
    }

    /** URL segment of a site's modern pages (/sites/hr/SitePages/home.aspx); no library or list may use it. */
    public static final String RESERVED_PAGES_NAME = "SitePages";
    private static final int MAX_SITE_DEPTH = 4;

    // ---- lookup ----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Node get(CurrentUser user, UUID id) {
        Node node = nodes.findLive(id).orElseThrow(() -> ApiException.notFound("Item"));
        permissions.require(user, node, Right.VIEW);
        return node;
    }

    /** Resolves a SharePoint-style server-relative URL such as {@code /sites/hr/Shared Documents/a.docx}. */
    @Transactional(readOnly = true)
    public Node resolve(CurrentUser user, String path) {
        // Try the path as given first (names may contain '%'), then URL-decoded (old SharePoint links).
        String raw = cleanPath(path);
        Optional<Node> node = nodes.findLiveByPath(raw).or(() -> nodes.findLiveByPath(normalizePath(path)));
        if (node.isPresent() && permissions.has(user, node.get(), Right.VIEW)) return node.get();
        throw refused(user, node.map(Node::getPath).orElse(normalizePath(path)));
    }

    /**
     * For an address typed into the browser. The answer depends only on the site in the address, never on what is
     * below it, so asking about a file says nothing about whether the file exists.
     */
    @Transactional(readOnly = true)
    public Node resolveAddress(CurrentUser user, String decodedPath) {
        Optional<Node> node = nodes.findLiveByPath(decodedPath);
        if (node.isPresent() && permissions.has(user, node.get(), Right.VIEW)) return node.get();
        throw refused(user, decodedPath);
    }

    private ApiException refused(CurrentUser user, String path) {
        if (user.anonymous()) return new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "sign_in_required", "Sign in to see this");
        for (String p = path; p != null && p.startsWith("/sites/"); p = p.substring(0, p.lastIndexOf('/'))) {
            Optional<Node> found = nodes.findLiveByPath(p).filter(n -> n.getKind() == Node.Kind.SITE);
            if (found.isEmpty()) continue;
            if (permissions.has(user, found.get(), Right.VIEW)) break;
            return new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "site_not_shared", "This site wasn't shared with you");
        }
        return ApiException.notFound("Item");
    }

    /** Like {@link #resolve} for a path that is already percent-decoded (e.g. a servlet path). */
    @Transactional(readOnly = true)
    public Node resolveDecoded(CurrentUser user, String decodedPath) {
        Node node = nodes.findLiveByPath(decodedPath).orElseThrow(() -> ApiException.notFound("Item"));
        permissions.require(user, node, Right.VIEW);
        return node;
    }

    @Transactional(readOnly = true)
    public List<Node> children(CurrentUser user, UUID parentId) {
        Node parent = get(user, parentId);
        boolean holdsFiles = parent.getKind().isContainer() || parent.getKind() == Node.Kind.LIST_ITEM; // attachments
        if (!holdsFiles) throw ApiException.badRequest("not_a_container", "Files have no children");
        return permissions.filterVisible(user, nodes.findLiveChildren(parent.getId()));
    }

    /** The chain from the site down to (and including) the node, for breadcrumbs. */
    @Transactional(readOnly = true)
    public List<Node> ancestry(Node node) {
        LinkedList<Node> chain = new LinkedList<>();
        Node current = node;
        while (current != null) {
            chain.addFirst(current);
            if (current.getKind() == Node.Kind.SITE) break; // a subsite's content starts at the subsite
            current = current.getParentId() == null ? null : nodes.findById(current.getParentId()).orElse(null);
        }
        return chain;
    }

    @Transactional(readOnly = true)
    public Node libraryOf(Node node) {
        if (node.getKind().hasColumns()) return node;
        if (node.getKind() == Node.Kind.SITE) throw ApiException.badRequest("not_in_library", "Sites are not in a library or list");
        Node current = node;
        while (!current.getKind().hasColumns()) {
            UUID parent = current.getParentId();
            current = parent == null ? null : nodes.findById(parent).orElse(null);
            if (current == null || current.getKind() == Node.Kind.SITE) {
                throw new IllegalStateException("No library for " + node.getPath());
            }
        }
        return current;
    }

    @Transactional(readOnly = true)
    public List<Node> visibleSites(CurrentUser user) {
        return permissions.filterVisible(user, nodes.findLiveSites());
    }

    @Transactional(readOnly = true)
    public List<Node> search(CurrentUser user, String q, int limit) {
        if (q == null || q.isBlank()) return List.of();
        List<Node> candidates = nodes.searchByName(q.strip(), PageRequest.of(0, Math.max(limit * 4, 100)));
        List<Node> visible = permissions.filterVisible(user, candidates);
        return visible.subList(0, Math.min(limit, visible.size()));
    }

    // ---- sites -----------------------------------------------------------------------------

    /**
     * Creates a team site with SharePoint's standard groups (Owners: Full Control, Members: Edit,
     * Visitors: Read), adds the creator to Owners, and provisions the default document library.
     */
    public Node createSite(CurrentUser user, String slug, String title, String description) {
        if (!selfServiceSites && !user.admin()) {
            throw ApiException.forbidden("Only administrators can create sites");
        }
        String name = Names.siteSlug(slug);
        if (nodes.existsSiteName(name)) {
            throw ApiException.conflict("site_exists", "A site with the URL /sites/" + name + " already exists");
        }
        Node site = Node.create(Node.Kind.SITE, null, name, user.id());
        site.setTitle(title == null || title.isBlank() ? name : title.strip());
        site.setDescription(description);
        nodes.save(site);
        return provision(user, site, true);
    }

    /**
     * Creates a site inside a site. With {@code uniquePermissions} it gets its own Owners, Members and Visitors, and its
     * creator owns it. Without, it shares its parent's permissions and groups.
     */
    public Node createSubsite(CurrentUser user, UUID parentId, String slug, String title, String description, boolean uniquePermissions) {
        Node parent = get(user, parentId);
        if (parent.getKind() != Node.Kind.SITE) throw ApiException.badRequest("not_a_site", "Subsites live in a site");
        permissions.require(user, parent, Right.MANAGE_WEB);
        int depth = parent.getPath().split("/").length - 2;
        if (depth >= MAX_SITE_DEPTH) throw ApiException.badRequest("too_deep", "Sites can be nested " + MAX_SITE_DEPTH + " levels deep");
        String name = Names.siteSlug(slug);
        assertNoSibling(parent, name);
        Node site = Node.create(Node.Kind.SITE, parent, name, user.id());
        site.setTitle(title == null || title.isBlank() ? name : title.strip());
        site.setDescription(description);
        site.setInheritsPermissions(!uniquePermissions);
        nodes.save(site);
        return provision(user, site, uniquePermissions);
    }

    @Transactional(readOnly = true)
    public List<Node> subsites(CurrentUser user, UUID siteId) {
        Node site = get(user, siteId);
        return permissions.filterVisible(user, nodes.findLiveChildren(site.getId()).stream().filter(n -> n.getKind() == Node.Kind.SITE).toList());
    }

    private Node provision(CurrentUser user, Node site, boolean ownGroups) {
        if (ownGroups) {
            String prefix = site.groupPrefix();
            String t = site.getTitle();
            Principal owners = principals.save(Principal.group(prefix + "-owners", t + " Owners", site.getId()));
            Principal membersGroup = principals.save(Principal.group(prefix + "-members", t + " Members", site.getId()));
            Principal visitors = principals.save(Principal.group(prefix + "-visitors", t + " Visitors", site.getId()));
            members.save(new GroupMember(owners.getId(), user.id()));
            permissions.grant(site, owners.getId(), PermissionLevel.FULL_CONTROL);
            permissions.grant(site, membersGroup.getId(), PermissionLevel.EDIT);
            permissions.grant(site, visitors.getId(), PermissionLevel.READ);
        }

        Node library = Node.create(Node.Kind.LIBRARY, site, DEFAULT_LIBRARY, user.id());
        library.setTitle("Documents");
        LibrarySettings.DEFAULT.applyTo(library);
        nodes.save(library);

        audit.record(user, "SITE_CREATE", site, Map.of("title", site.getTitle()));
        return site;
    }

    public Node updateSite(CurrentUser user, UUID siteId, String title, String description) {
        Node site = get(user, siteId);
        if (site.getKind() != Node.Kind.SITE) throw ApiException.badRequest("not_a_site", "Not a site");
        permissions.require(user, site, Right.MANAGE_WEB);
        if (title != null && !title.isBlank()) site.setTitle(title.strip());
        if (description != null) site.setDescription(description);
        site.touch(user.id());
        audit.record(user, "SITE_UPDATE", site);
        return site;
    }

    // ---- containers ------------------------------------------------------------------------

    public Node createLibrary(CurrentUser user, UUID siteId, String rawName, String title, String description) {
        Node site = get(user, siteId);
        if (site.getKind() != Node.Kind.SITE) throw ApiException.badRequest("not_a_site", "Libraries live directly in a site");
        permissions.require(user, site, Right.MANAGE_LISTS);
        String name = Names.item(rawName);
        assertNoSibling(site, name);
        Node lib = Node.create(Node.Kind.LIBRARY, site, name, user.id());
        lib.setTitle(title == null || title.isBlank() ? name : title.strip());
        lib.setDescription(description);
        LibrarySettings.DEFAULT.applyTo(lib);
        nodes.save(lib);
        audit.record(user, "LIBRARY_CREATE", lib);
        return lib;
    }

    /** Creates a list in a site, provisioned with the template's columns. */
    public Node createList(CurrentUser user, UUID siteId, String rawName, String title, String description,
                           ListTemplate template) {
        Node site = get(user, siteId);
        if (site.getKind() != Node.Kind.SITE) throw ApiException.badRequest("not_a_site", "Lists live directly in a site");
        permissions.require(user, site, Right.MANAGE_LISTS);
        String name = Names.item(rawName);
        assertNoSibling(site, name);
        ListTemplate t = template == null ? ListTemplate.CUSTOM : template;
        Node list = Node.create(Node.Kind.LIST, site, name, user.id());
        list.setTitle(title == null || title.isBlank() ? name : title.strip());
        list.setDescription(description == null || description.isBlank() ? t.description() : description);
        Map<String, Object> settings = new HashMap<>();
        settings.put("template", t.name());
        settings.put("nextItemId", 1);
        list.setSettings(settings);
        nodes.save(list);
        int position = 0;
        for (ListTemplate.Column c : t.columns()) {
            columns.save(new ColumnDef(list.getId(), Names.columnInternalName(c.displayName()), c.displayName(),
                    c.type(), c.required(), c.choices(), position++));
        }
        audit.record(user, "LIST_CREATE", list, Map.of("template", t.name()));
        return list;
    }

    /** Adds an item to a list. Items are numbered per list, like SharePoint item IDs. */
    public Node createItem(CurrentUser user, UUID listId, String title, Map<String, Object> values) {
        Node list = get(user, listId);
        if (list.getKind() != Node.Kind.LIST) throw ApiException.badRequest("not_a_list", "Not a list");
        permissions.require(user, list, Right.ADD_ITEMS);
        if (title == null || title.isBlank()) throw ApiException.badRequest("required_column", "'Title' is required");
        if (title.length() > 255) throw ApiException.badRequest("invalid_value", "'Title' is limited to 255 characters");
        Map<String, Object> metadata = validateMetadata(list, Map.of(), values == null ? Map.of() : values);
        Map<String, Object> settings = new HashMap<>(list.getSettings());
        int itemId = settings.get("nextItemId") instanceof Number n ? n.intValue() : 1;
        while (nodes.existsLiveSibling(list.getId(), String.valueOf(itemId))) itemId++;
        settings.put("nextItemId", itemId + 1);
        list.setSettings(settings);
        Node item = Node.create(Node.Kind.LIST_ITEM, list, String.valueOf(itemId), user.id());
        item.setTitle(title.strip());
        item.setMetadata(metadata);
        nodes.save(item);
        list.touch(user.id());
        itemHistory.record(item, user.id());
        audit.record(user, "ITEM_CREATE", item, Map.of("title", item.getTitle()));
        return item;
    }

    public Node createFolder(CurrentUser user, UUID parentId, String rawName) {
        Node parent = get(user, parentId);
        if (parent.getKind() != Node.Kind.LIBRARY && parent.getKind() != Node.Kind.FOLDER) {
            throw ApiException.badRequest("invalid_parent", "Folders can only be created in a library or folder");
        }
        permissions.require(user, parent, Right.ADD_ITEMS);
        String name = Names.item(rawName);
        assertNoSibling(parent, name);
        Node folder = Node.create(Node.Kind.FOLDER, parent, name, user.id());
        nodes.save(folder);
        audit.record(user, "FOLDER_CREATE", folder);
        return folder;
    }

    public LibrarySettings updateLibrarySettings(CurrentUser user, UUID libraryId, LibrarySettings settings) {
        Node lib = get(user, libraryId);
        if (lib.getKind() != Node.Kind.LIBRARY) throw ApiException.badRequest("not_a_library", "Not a library");
        permissions.require(user, lib, Right.MANAGE_LISTS);
        if (settings.maxVersions() != null && settings.maxVersions() < 1) {
            throw ApiException.badRequest("invalid_settings", "maxVersions must be at least 1");
        }
        boolean hidDrafts = LibrarySettings.of(lib).hidesDrafts();
        settings.applyTo(lib);
        if (hidDrafts != settings.hidesDrafts()) search.subtree(lib.getId()); // the index must hold what readers may find
        lib.touch(user.id());
        audit.record(user, "LIBRARY_SETTINGS_UPDATE", lib, Map.of(
                "versioningEnabled", settings.versioningEnabled(),
                "minorVersionsEnabled", settings.minorVersionsEnabled(),
                "requireCheckout", settings.requireCheckout(),
                "draftVisibility", settings.draftVisibility().name()));
        return settings;
    }

    // ---- rename / move / metadata ------------------------------------------------------------

    /** Updates name, title, description and/or metadata. Any argument may be null to leave it unchanged. */
    public Node update(CurrentUser user, UUID id, String newName, String title, String description,
                       Map<String, Object> metadataPatch) {
        Node node = get(user, id);
        Right needed = switch (node.getKind()) {
            case SITE -> Right.MANAGE_WEB;
            case LIBRARY, LIST -> Right.MANAGE_LISTS;
            default -> Right.EDIT_ITEMS;
        };
        permissions.require(user, node, needed);
        assertNotLockedByOthers(user, node);
        boolean item = node.getKind() == Node.Kind.LIST_ITEM;
        String oldTitle = node.getTitle();
        Map<String, Object> oldValues = new HashMap<>(node.getMetadata());
        if (item) {
            itemHistory.baseline(node);
            if (newName != null && !newName.equals(node.getName())) {
                throw ApiException.badRequest("item_id_immutable", "List item IDs cannot be changed; edit the Title instead");
            }
            if (title != null && title.isBlank()) throw ApiException.badRequest("required_column", "'Title' is required");
        }

        if (newName != null && !newName.equals(node.getName())) {
            if (node.getKind() == Node.Kind.SITE) {
                throw ApiException.badRequest("site_url_immutable", "A site's URL cannot be changed; change its title instead");
            }
            String name = Names.item(newName);
            Node parent = nodes.findById(node.getParentId()).orElseThrow();
            if (!name.equalsIgnoreCase(node.getName())) assertNoSibling(parent, name);
            String oldPath = node.getPath();
            node.setName(name);
            relocate(node, parent);
            audit.record(user, "RENAME", node, Map.of("from", oldPath));
        }
        if (title != null) node.setTitle(title.isBlank() ? null : title.strip());
        if (description != null) node.setDescription(description);
        if (metadataPatch != null) {
            if (node.getKind() != Node.Kind.FILE && node.getKind() != Node.Kind.FOLDER
                    && node.getKind() != Node.Kind.LIST_ITEM) {
                throw ApiException.badRequest("no_metadata", "Only files, folders and list items carry column values");
            }
            Map<String, Object> merged = validateMetadata(libraryOf(node), node.getMetadata(), metadataPatch);
            node.setMetadata(merged);
            audit.record(user, "METADATA_UPDATE", node, Map.of("fields", new ArrayList<>(metadataPatch.keySet())));
        }
        node.touch(user.id());
        search.node(node.getId()); // title and description changes have no audit event of their own
        if (item && !(Objects.equals(oldTitle, node.getTitle()) && oldValues.equals(node.getMetadata()))) {
            itemHistory.record(node, user.id());
            if (!Objects.equals(oldTitle, node.getTitle())) refreshLookups(node);
            if (metadataPatch == null) audit.record(user, "ITEM_UPDATE", node, Map.of("title", String.valueOf(node.getTitle())));
        }
        return node;
    }

    public Node move(CurrentUser user, UUID id, UUID targetParentId) {
        Node node = get(user, id);
        if (node.getKind() == Node.Kind.SITE) throw ApiException.badRequest("site_url_immutable", "A site cannot be moved");
        if (node.getKind() != Node.Kind.FILE && node.getKind() != Node.Kind.FOLDER) {
            throw ApiException.badRequest("not_movable", "Only files and folders can be moved");
        }
        Node target = get(user, targetParentId);
        if (target.getKind() != Node.Kind.LIBRARY && target.getKind() != Node.Kind.FOLDER) {
            throw ApiException.badRequest("invalid_target", "Items can only be moved into a library or folder");
        }
        if (target.getId().equals(node.getParentId())) return node;
        if (target.getId().equals(node.getId()) || target.getPath().toLowerCase(Locale.ROOT)
                .startsWith(node.getPath().toLowerCase(Locale.ROOT) + "/")) {
            throw ApiException.badRequest("invalid_target", "A folder cannot be moved into itself");
        }
        permissions.require(user, node, Right.DELETE_ITEMS);
        permissions.require(user, target, Right.ADD_ITEMS);
        assertNotLockedByOthers(user, node);
        assertNoSibling(target, node.getName());
        quotas.admitMove(node, target);

        String oldPath = node.getPath();
        relocate(node, target);
        node.touch(user.id());
        audit.record(user, "MOVE", node, Map.of("from", oldPath));
        return node;
    }

    /** Re-parents {@code node} under {@code parent} (using its current name) and rewrites descendant paths. */
    private void relocate(Node node, Node parent) {
        String oldPath = node.getPath();
        String newPath = parent.getPath() + "/" + node.getName();
        node.setParentId(parent.getId());
        node.setSiteId(parent.getSiteId());
        node.setPath(newPath);
        nodes.saveAndFlush(node);
        if (node.getKind().isContainer()) {
            nodes.rewriteDescendantPaths(NodeRepository.likePrefix(oldPath), oldPath.length(), newPath, parent.getSiteId());
        }
    }

    // ---- delete & recycle bin ----------------------------------------------------------------

    public void delete(CurrentUser user, UUID id) {
        Node node = get(user, id);
        switch (node.getKind()) {
            case SITE -> {
                boolean parentOwner = node.getParentId() != null && nodes.findLive(node.getParentId())
                        .filter(parent -> permissions.has(user, parent, Right.MANAGE_WEB)).isPresent();
                if (!user.admin() && !parentOwner) throw ApiException.forbidden("Only administrators can delete sites");
            }
            case LIBRARY, LIST -> permissions.require(user, node, Right.MANAGE_LISTS);
            default -> permissions.require(user, node, Right.DELETE_ITEMS);
        }
        assertNotLockedByOthers(user, node);
        if (node.getKind().isContainer()
                && nodes.countDescendantsCheckedOutByOthers(NodeRepository.likePrefix(node.getPath()), user.id()) > 0) {
            throw ApiException.locked("This folder contains files checked out by other people");
        }
        nodes.softDeleteSubtree(node.getId(), NodeRepository.likePrefix(node.getPath()), Instant.now(), user.id());
        audit.record(user, "DELETE", node);
    }

    @Transactional(readOnly = true)
    public List<Node> recycleBin(CurrentUser user, UUID siteId) {
        return nodes.findRecycleBin(siteId).stream()
                .filter(n -> canManageDeleted(user, n))
                .toList();
    }

    public Node restore(CurrentUser user, UUID id) {
        Node root = deletedRoot(user, id);
        if (root.getParentId() != null) {
            Node parent = nodes.findLive(root.getParentId()).orElseThrow(() -> ApiException.conflict(
                    "parent_deleted", "The original location no longer exists; restore the parent folder first"));
            if (nodes.existsLiveSibling(parent.getId(), root.getName())) {
                throw ApiException.conflict("name_conflict",
                        "An item named '" + root.getName() + "' already exists in the original location");
            }
        }
        nodes.restoreSubtree(root.getId());
        audit.record(user, "RESTORE", root);
        return nodes.findById(root.getId()).orElseThrow();
    }

    /** Lookup values keep the title they point at, so a renamed item must be renamed everywhere it is used. */
    private void refreshLookups(Node item) {
        for (ColumnDef column : columns.findByLookupListId(item.getParentId())) {
            for (Node row : nodes.findLiveChildren(column.getLibraryId())) {
                if (row.getMetadata().get(column.getInternalName()) instanceof Map<?, ?> value
                        && item.getName().equals(String.valueOf(value.get("lookupId")))) {
                    Map<String, Object> metadata = new HashMap<>(row.getMetadata());
                    metadata.put(column.getInternalName(), lookupValue(item));
                    row.setMetadata(metadata);
                    search.node(row.getId());
                }
            }
        }
    }

    private static Map<String, Object> lookupValue(Node item) {
        return Map.of("lookupId", Integer.parseInt(item.getName()), "title", item.getTitle());
    }

    @Transactional(readOnly = true)
    public List<ItemVersion> itemHistory(CurrentUser user, UUID itemId) {
        return itemVersions.findByItemIdOrderByVersionDesc(listItem(user, itemId).getId());
    }

    /** Makes an earlier version current again, as a new version, so nothing is lost. */
    public Node restoreItemVersion(CurrentUser user, UUID itemId, int version) {
        Node item = listItem(user, itemId);
        permissions.require(user, item, Right.EDIT_ITEMS);
        assertNotLockedByOthers(user, item);
        ItemVersion v = itemVersions.findByItemIdAndVersion(item.getId(), version).orElseThrow(() -> ApiException.notFound("Version"));
        itemHistory.baseline(item);
        boolean renamed = !Objects.equals(item.getTitle(), v.getTitle());
        item.setTitle(v.getTitle());
        item.setMetadata(new HashMap<>(v.getMetadata()));
        item.touch(user.id());
        nodes.save(item);
        itemHistory.record(item, user.id());
        if (renamed) refreshLookups(item);
        audit.record(user, "ITEM_RESTORE", item, Map.of("version", version));
        return item;
    }

    private Node listItem(CurrentUser user, UUID itemId) {
        Node item = get(user, itemId);
        if (item.getKind() != Node.Kind.LIST_ITEM) throw ApiException.badRequest("not_an_item", "Not a list item");
        return item;
    }

    /** Images attached to the items of a list that the user can see. */
    @Transactional(readOnly = true)
    public List<Node> attachedImages(CurrentUser user, UUID listId) {
        Node list = get(user, listId);
        if (list.getKind() != Node.Kind.LIST) throw ApiException.badRequest("not_a_list", "Not a list");
        List<Node> images = nodes.findLiveDescendants(NodeRepository.likePrefix(list.getPath())).stream()
                .filter(n -> n.getKind() == Node.Kind.FILE && n.getMimeType() != null && n.getMimeType().startsWith("image/"))
                .sorted(Comparator.comparing(Node::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        return permissions.filterVisible(user, images);
    }

    /** The files in the site's Templates library. */
    @Transactional(readOnly = true)
    public List<Node> documentTemplates(CurrentUser user, UUID siteId) {
        Node site = get(user, siteId);
        return nodes.findLiveByPath(site.getPath() + "/Templates").filter(n -> n.getKind() == Node.Kind.LIBRARY)
                .map(library -> permissions.filterVisible(user, nodes.findLiveChildren(library.getId())).stream()
                        .filter(n -> n.getKind() == Node.Kind.FILE).toList())
                .orElse(List.of());
    }

    /** Permanently deletes a recycle-bin item, its descendants, all versions and unreferenced blobs. */
    public void purge(CurrentUser user, UUID id) {
        Node root = deletedRoot(user, id);
        List<Node> subtree = wholeSubtree(root);
        // children before parents so the parent_id foreign key is never violated
        subtree.sort(Comparator.comparingInt((Node n) -> n.getPath().length()).reversed());
        List<UUID> ids = subtree.stream().map(Node::getId).toList();
        List<FileVersion> allVersions = versions.findByNodeIdIn(ids);
        Set<String> keys = new HashSet<>();
        allVersions.forEach(v -> keys.add(v.getStorageKey()));
        subtree.forEach(n -> keys.add(n.getWorkingStorageKey()));
        versions.deleteAll(allVersions);
        for (Node n : subtree) {
            columns.deleteAll(columns.findByLibraryIdOrderByPosition(n.getId()));
            nodes.delete(n);
            nodes.flush();
            search.node(n.getId());
        }
        // a purged site frees its URL, so its Owners, Members and Visitors groups go too, and those of its subsites
        for (Node n : subtree) {
            if (n.getKind() == Node.Kind.SITE) principals.deleteAll(principals.findBySiteId(n.getId()));
        }
        gc.deleteIfOrphanedAfterCommit(keys);
        audit.record(user, "PURGE", root, Map.of("items", subtree.size()));
    }

    /** The item and everything under it, including children deleted earlier, which have their own recycle bin entry. */
    private List<Node> wholeSubtree(Node root) {
        Map<UUID, Node> found = new LinkedHashMap<>();
        found.put(root.getId(), root);
        Set<UUID> frontier = Set.of(root.getId());
        while (!frontier.isEmpty()) {
            Set<UUID> next = new HashSet<>();
            for (Node child : nodes.findByParentIdIn(frontier)) {
                if (found.putIfAbsent(child.getId(), child) == null) next.add(child.getId());
            }
            frontier = next;
        }
        return new ArrayList<>(found.values());
    }

    private Node deletedRoot(CurrentUser user, UUID id) {
        Node root = nodes.findById(id)
                .filter(n -> n.isDeleted() && id.equals(n.getDeletedRootId()))
                .orElseThrow(() -> ApiException.notFound("Recycle bin item"));
        if (!canManageDeleted(user, root)) throw ApiException.notFound("Recycle bin item");
        return root;
    }

    private boolean canManageDeleted(CurrentUser user, Node deleted) {
        if (user.admin()) return true;
        if (deleted.getKind() == Node.Kind.SITE) {
            // whoever runs the parent site looks after its subsites
            return deleted.getParentId() != null && nodes.findById(deleted.getParentId())
                    .filter(parent -> !parent.isDeleted() && permissions.has(user, parent, Right.MANAGE_WEB)).isPresent();
        }
        if (user.id().equals(deleted.getDeletedBy())) return true;
        return nodes.findById(deleted.getSiteId())
                .map(site -> permissions.has(user, site, Right.MANAGE_WEB))
                .orElse(false);
    }

    // ---- columns -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ColumnDef> columns(CurrentUser user, UUID libraryId) {
        Node lib = get(user, libraryId);
        return columns.findByLibraryIdOrderByPosition(libraryOf(lib).getId());
    }

    public ColumnDef addColumn(CurrentUser user, UUID libraryId, String displayName, ColumnDef.Type type,
                               boolean required, List<String> choices, UUID lookupListId) {
        Node lib = get(user, libraryId);
        if (!lib.getKind().hasColumns()) throw ApiException.badRequest("not_a_library", "Not a library or list");
        permissions.require(user, lib, Right.MANAGE_LISTS);
        if (displayName == null || displayName.isBlank()) {
            throw ApiException.badRequest("invalid_column", "Column name is required");
        }
        if (type == ColumnDef.Type.CHOICE && (choices == null || choices.isEmpty())) {
            throw ApiException.badRequest("invalid_column", "A choice column needs at least one choice");
        }
        if (type == ColumnDef.Type.LOOKUP) {
            if (lookupListId == null) throw ApiException.badRequest("invalid_column", "A lookup column needs a list to look up");
            Node target = get(user, lookupListId);
            if (target.getKind() != Node.Kind.LIST || !target.getSiteId().equals(lib.getSiteId())) {
                throw ApiException.badRequest("invalid_column", "A lookup column must point at a list in the same site");
            }
        }
        List<ColumnDef> existing = columns.findByLibraryIdOrderByPosition(lib.getId());
        String base = Names.columnInternalName(displayName);
        String internal = base;
        Set<String> taken = new HashSet<>(Set.of("Name", "Title", "Created", "Modified", "Author", "Editor", "Version"));
        existing.forEach(c -> taken.add(c.getInternalName()));
        for (int i = 1; taken.contains(internal); i++) internal = base + i;
        ColumnDef col = columns.save(new ColumnDef(lib.getId(), internal, displayName.strip(), type, required,
                choices, existing.size()));
        if (type == ColumnDef.Type.LOOKUP) col.setLookupListId(lookupListId);
        audit.record(user, "COLUMN_ADD", lib, Map.of("column", internal, "type", type.name()));
        return col;
    }

    public void deleteColumn(CurrentUser user, UUID libraryId, UUID columnId) {
        Node lib = get(user, libraryId);
        permissions.require(user, lib, Right.MANAGE_LISTS);
        ColumnDef col = columns.findById(columnId)
                .filter(c -> c.getLibraryId().equals(lib.getId()))
                .orElseThrow(() -> ApiException.notFound("Column"));
        columns.delete(col);
        audit.record(user, "COLUMN_DELETE", lib, Map.of("column", col.getInternalName()));
    }

    /** Validates a metadata patch against the library's columns; a null value clears a field. */
    Map<String, Object> validateMetadata(Node library, Map<String, Object> current, Map<String, Object> patch) {
        Map<String, ColumnDef> defs = new HashMap<>();
        columns.findByLibraryIdOrderByPosition(library.getId()).forEach(c -> defs.put(c.getInternalName(), c));
        Map<String, Object> merged = new HashMap<>(current);
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            ColumnDef def = defs.get(e.getKey());
            if (def == null) throw ApiException.badRequest("unknown_column", "Unknown column '" + e.getKey() + "'");
            Object value = e.getValue();
            if (value == null || (value instanceof String s && s.isBlank())) {
                merged.remove(def.getInternalName());
            } else {
                merged.put(def.getInternalName(), coerce(def, value));
            }
        }
        for (ColumnDef def : defs.values()) {
            if (def.isRequired() && !merged.containsKey(def.getInternalName())) {
                throw ApiException.badRequest("required_column", "'" + def.getDisplayName() + "' is required");
            }
        }
        return merged;
    }

    private Object coerce(ColumnDef def, Object value) {
        String label = "'" + def.getDisplayName() + "'";
        return switch (def.getType()) {
            case TEXT -> {
                String s = value.toString();
                if (s.length() > 255) throw ApiException.badRequest("invalid_value", label + " is limited to 255 characters");
                yield s;
            }
            case NOTE -> value.toString();
            case NUMBER -> {
                try {
                    yield value instanceof Number n ? n : new BigDecimal(value.toString().strip());
                } catch (NumberFormatException ex) {
                    throw ApiException.badRequest("invalid_value", label + " must be a number");
                }
            }
            case BOOLEAN -> {
                if (value instanceof Boolean b) yield b;
                String s = value.toString().strip().toLowerCase(Locale.ROOT);
                if (s.equals("true") || s.equals("yes")) yield true;
                if (s.equals("false") || s.equals("no")) yield false;
                throw ApiException.badRequest("invalid_value", label + " must be yes or no");
            }
            case DATE -> {
                String s = value.toString().strip();
                try {
                    if (s.length() == 10) LocalDate.parse(s); else OffsetDateTime.parse(s);
                    yield s;
                } catch (DateTimeParseException ex) {
                    throw ApiException.badRequest("invalid_value", label + " must be a date (YYYY-MM-DD)");
                }
            }
            case LOOKUP -> {
                Object ref = value instanceof Map<?, ?> m ? m.get("lookupId") : value;
                String itemId = String.valueOf(ref).strip();
                Node item = !itemId.matches("\\d{1,9}") || def.getLookupListId() == null ? null : nodes.findLive(def.getLookupListId())
                        .flatMap(list -> nodes.findLiveByPath(list.getPath() + "/" + itemId))
                        .filter(n -> n.getKind() == Node.Kind.LIST_ITEM).orElse(null);
                if (item == null) throw ApiException.badRequest("invalid_value", label + " must be an item of the list it looks up");
                yield lookupValue(item);
            }
            case CHOICE -> {
                String s = value.toString();
                if (!def.getChoices().contains(s)) {
                    throw ApiException.badRequest("invalid_value", label + " must be one of " + def.getChoices());
                }
                yield s;
            }
            case URL -> {
                String s = value.toString().strip();
                if (!SafeUrls.isSafe(s)) {
                    throw ApiException.badRequest("invalid_value", label + " must be a web address (https://...) or a /sites/... path");
                }
                yield s;
            }
            case PERSON -> {
                // accept a principal id, a login, or an object with an "id"; store a small snapshot for display
                String ref = value instanceof Map<?, ?> m && m.get("id") != null ? m.get("id").toString() : value.toString().strip();
                Principal p;
                try {
                    p = principals.findById(UUID.fromString(ref)).orElse(null);
                } catch (IllegalArgumentException notUuid) {
                    p = principals.findByKindAndLogin(Principal.Kind.USER, ref)
                            .or(() -> principals.findByKindAndLogin(Principal.Kind.GROUP, ref)).orElse(null);
                }
                if (p == null || !p.isEnabled()) throw ApiException.badRequest("invalid_value", label + ": unknown person");
                yield Map.of("id", p.getId().toString(), "login", p.getLogin(), "displayName", p.getDisplayName());
            }
        };
    }

    // ---- helpers -----------------------------------------------------------------------------

    void assertNoSibling(Node parent, String name) {
        if (parent.getKind() == Node.Kind.SITE && name.equalsIgnoreCase(RESERVED_PAGES_NAME)) {
            throw ApiException.conflict("name_reserved", "'" + RESERVED_PAGES_NAME + "' is reserved for the site's pages");
        }
        if (nodes.existsLiveSibling(parent.getId(), name)) {
            throw ApiException.conflict("name_conflict", "An item named '" + name + "' already exists here");
        }
    }

    void assertNotLockedByOthers(CurrentUser user, Node node) {
        if (node.isCheckedOut() && !node.getCheckedOutBy().equals(user.id())) {
            throw ApiException.locked("'" + node.getName() + "' is checked out by someone else");
        }
        if (node.getKind() == Node.Kind.FILE && editLocks.lockedByOther(user, node)) {
            throw ApiException.locked("'" + node.getName() + "' is open for editing by someone else");
        }
    }

    static String cleanPath(String path) {
        if (path == null || path.isBlank()) throw ApiException.badRequest("invalid_path", "Path is required");
        String p = path.strip().replace('\\', '/');
        if (!p.startsWith("/")) p = "/" + p;
        while (p.contains("//")) p = p.replace("//", "/");
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    static String normalizePath(String path) {
        if (path == null || path.isBlank()) throw ApiException.badRequest("invalid_path", "Path is required");
        String p = path.strip().replace('\\', '/');
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        try {
            p = java.net.URLDecoder.decode(p.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notEncoded) {
            // a literal '%' in a name: keep the path as is
        }
        if (!p.startsWith("/")) p = "/" + p;
        while (p.contains("//")) p = p.replace("//", "/");
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
