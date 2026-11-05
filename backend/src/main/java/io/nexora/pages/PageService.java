package io.nexora.pages;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.content.ContentService;
import io.nexora.content.DocumentService;
import io.nexora.content.LibrarySettings;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.identity.CurrentUser;
import io.nexora.search.SearchQueue;
import io.nexora.portal.PortalService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modern pages and news posts.
 *
 * <p>Rights are taken from the site, like SharePoint's Site Pages library: everyone who can see the site reads
 * published pages; Contribute creates, edits and publishes (ADD_ITEMS, EDIT_ITEMS), deleting needs DELETE_ITEMS
 * and choosing the site's home page needs MANAGE_WEB. Pages that were never published are only visible to
 * people who can edit them.
 */
@Service
@Transactional
public class PageService {
    public static final String ASSETS_LIBRARY = "SiteAssets";
    public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final String HOME_SETTING = "homePageId";
    private static final Pattern PAGE_PATH = Pattern.compile(
            "^(/sites/.+)/" + ContentService.RESERVED_PAGES_NAME + "/([^/]+?)(?:\\.aspx)?/?$", Pattern.CASE_INSENSITIVE);

    public record Content(String title, String description, UUID bannerImageId, Canvas canvas) {}

    public record Draft(String title, String description, UUID bannerImageId, Canvas canvas, Integer revision) {}

    private final ContentService content;
    private final DocumentService documents;
    private final NodeRepository nodes;
    private final PermissionService permissions;
    private final SitePageRepository pages;
    private final SitePageVersionRepository versions;
    private final AuditService audit;
    private final SearchQueue search;
    private final EmbedPolicy embeds;
    private final ObjectMapper json;

    public PageService(ContentService content, DocumentService documents, NodeRepository nodes,
                       PermissionService permissions, SitePageRepository pages, SitePageVersionRepository versions,
                       AuditService audit, SearchQueue search, EmbedPolicy embeds, ObjectMapper json) {
        this.embeds = embeds;
        this.content = content;
        this.documents = documents;
        this.nodes = nodes;
        this.permissions = permissions;
        this.pages = pages;
        this.versions = versions;
        this.audit = audit;
        this.search = search;
        this.json = json;
    }

    // ---- reading -------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Node site(CurrentUser user, UUID siteId) {
        Node site = content.get(user, siteId);
        if (site.getKind() != Node.Kind.SITE) throw ApiException.badRequest("not_a_site", "Not a site");
        return site;
    }

    /** Pages of a site the caller may see: everything for editors, published pages for everyone else. */
    @Transactional(readOnly = true)
    public List<SitePage> list(CurrentUser user, Node site, SitePage.Kind kind) {
        boolean editor = can(user, site, Right.EDIT_ITEMS);
        Comparator<SitePage> order = kind == SitePage.Kind.NEWS
                ? Comparator.comparing(SitePage::getPublishedAt, Comparator.nullsFirst(Comparator.reverseOrder()))
                : Comparator.comparing((SitePage p) -> currentTitle(p).toLowerCase(Locale.ROOT));
        return pages.findBySiteId(site.getId()).stream()
                .filter(p -> kind == null ? p.getKind() != SitePage.Kind.TEMPLATE : p.getKind() == kind)
                .filter(p -> editor || p.isPublished())
                .sorted(order)
                .toList();
    }

    /** The latest published news posts, newest first. */
    @Transactional(readOnly = true)
    public List<SitePage> news(CurrentUser user, Node site, int limit) {
        return pages.findPublished(site.getId(), SitePage.Kind.NEWS, PageRequest.of(0, Math.max(1, Math.min(limit, 50))));
    }

    @Transactional(readOnly = true)
    public SitePage get(CurrentUser user, UUID pageId) {
        SitePage page = pages.findById(pageId).orElseThrow(() -> ApiException.notFound("Page"));
        return visible(user, site(user, page.getSiteId()), page);
    }

    /** Resolves {@code /sites/hr/SitePages/welcome.aspx}. */
    @Transactional(readOnly = true)
    public SitePage resolve(CurrentUser user, String path) {
        Matcher m = PAGE_PATH.matcher(path == null ? "" : path);
        if (!m.matches()) throw ApiException.notFound("Page");
        Node site = content.resolveAddress(user, m.group(1));
        if (site.getKind() != Node.Kind.SITE) throw ApiException.notFound("Page");
        SitePage page = pages.findBySiteIdAndName(site.getId(), m.group(2).toLowerCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.notFound("Page"));
        return visible(user, site, page);
    }

    @Transactional(readOnly = true)
    public List<SitePageVersion> versions(CurrentUser user, UUID pageId) {
        SitePage page = editable(user, pageId);
        return versions.findByPageIdOrderByVersionDesc(page.getId());
    }

    public Content published(SitePage p) {
        return p.isPublished() ? new Content(p.getTitle(), p.getDescription(), p.getBannerImageId(), canvas(p.getCanvas())) : null;
    }

    public Content draft(SitePage p) {
        if (!p.hasDraft()) return null;
        return new Content(p.getDraftTitle(), p.getDraftDescription(), p.getDraftBannerId(), canvas(p.getDraftCanvas()));
    }

    /** What a reader sees, or else the draft (for a page editors are still preparing). */
    public Content current(SitePage p) {
        return p.isPublished() ? published(p) : draft(p);
    }

    public static String currentTitle(SitePage p) {
        return p.isPublished() ? p.getTitle() : p.getDraftTitle();
    }

    public String url(Node site, SitePage p) {
        return site.getPath() + "/" + ContentService.RESERVED_PAGES_NAME + "/" + p.getName() + ".aspx";
    }

    public boolean can(CurrentUser user, Node site, Right right) {
        return permissions.has(user, site, right);
    }

    /** The site's chosen home page, if it is set and still published. */
    @Transactional(readOnly = true)
    public Optional<UUID> homePageId(Node site) {
        Object raw = site.getSettings().get(HOME_SETTING);
        if (raw == null) return Optional.empty();
        try {
            UUID id = UUID.fromString(raw.toString());
            return pages.findById(id).filter(p -> p.getSiteId().equals(site.getId()) && p.isPublished()).map(SitePage::getId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ---- writing -------------------------------------------------------------------------------

    public record TemplateInfo(String id, String title, String description, boolean custom) {}

    /** What a new page can start from: the built-in layouts, and what this site saved. */
    @Transactional(readOnly = true)
    public List<TemplateInfo> templates(CurrentUser user, Node site) {
        permissions.require(user, site, Right.ADD_ITEMS);
        List<TemplateInfo> out = new ArrayList<>();
        for (PageTemplate t : PageTemplate.values()) out.add(new TemplateInfo(t.name(), t.title(), t.description(), false));
        pages.findBySiteId(site.getId()).stream().filter(p -> p.getKind() == SitePage.Kind.TEMPLATE)
                .sorted(Comparator.comparing(p -> currentTitle(p).toLowerCase(Locale.ROOT)))
                .forEach(p -> out.add(new TemplateInfo(p.getId().toString(), currentTitle(p), p.getDraftDescription(), true)));
        return out;
    }

    /** Keeps a page's layout and content as a starting point for new pages in this site. */
    public SitePage saveAsTemplate(CurrentUser user, UUID pageId, String title) {
        SitePage source = get(user, pageId);
        Node site = siteOf(source);
        permissions.require(user, site, Right.ADD_ITEMS);
        if (source.getKind() == SitePage.Kind.TEMPLATE) throw ApiException.badRequest("is_template", "This is already a template");
        String t = title(title);
        Content c = current(source);
        SitePage template = new SitePage(site.getId(), uniqueName(site, "template-" + t), SitePage.Kind.TEMPLATE, t, user.id());
        template.saveDraft(t, c.description(), c.bannerImageId(), map(c.canvas()), user.id());
        pages.save(template);
        audit.record(user, "PAGE_TEMPLATE_CREATE", site, Map.of("template", t));
        return template;
    }

    private Canvas startFrom(Node site, String template) {
        for (PageTemplate t : PageTemplate.values()) if (t.name().equals(template)) return t.canvas().validated();
        UUID id;
        try {
            id = UUID.fromString(template);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Template");
        }
        SitePage saved = pages.findById(id).filter(p -> p.getKind() == SitePage.Kind.TEMPLATE && p.getSiteId().equals(site.getId()))
                .orElseThrow(() -> ApiException.notFound("Template"));
        return canvas(saved.getDraftCanvas());
    }

    public SitePage create(CurrentUser user, UUID siteId, String title, SitePage.Kind kind, String template) {
        Node site = site(user, siteId);
        permissions.require(user, site, Right.ADD_ITEMS);
        String t = title(title);
        SitePage.Kind k = kind == null ? SitePage.Kind.PAGE : kind;
        if (k == SitePage.Kind.TEMPLATE) throw ApiException.badRequest("invalid_kind", "Use Save as template on a page");
        SitePage page = new SitePage(site.getId(), uniqueName(site, t), k, t, user.id());
        page.saveDraft(t, "", null, map(template == null || template.isBlank() ? starter(k) : startFrom(site, template)), user.id());
        pages.save(page);
        audit.record(user, k == SitePage.Kind.NEWS ? "NEWS_CREATE" : "PAGE_CREATE", site, Map.of("page", page.getName()));
        return page;
    }

    public SitePage saveDraft(CurrentUser user, UUID pageId, Draft d) {
        SitePage page = editable(user, pageId);
        if (d.revision() != null && d.revision() != page.getRevision()) {
            throw ApiException.conflict("page_changed",
                    "Someone else changed this page while you were editing it. Reload it to see their changes.");
        }
        Canvas canvas = (d.canvas() == null ? Canvas.empty() : d.canvas()).validated(new HashSet<>(embeds.hosts()));
        String description = d.description() == null ? "" : d.description().strip();
        if (description.length() > 1000) throw ApiException.badRequest("invalid_page", "The description is too long (1000 characters)");
        for (UUID ref : canvas.references()) sameSite(page, ref, null);
        if (d.bannerImageId() != null) sameSite(page, d.bannerImageId(), Node.Kind.FILE);
        page.saveDraft(title(d.title()), description, d.bannerImageId(), map(canvas), user.id());
        return page;
    }

    public SitePage publish(CurrentUser user, UUID pageId) {
        SitePage page = editable(user, pageId);
        if (page.getKind() == SitePage.Kind.TEMPLATE) throw ApiException.badRequest("is_template", "Templates are not published. Create a page from it");
        if (!page.hasDraft()) throw ApiException.conflict("nothing_to_publish", "There are no changes to publish");
        page.publish(user.id());
        versions.save(SitePageVersion.of(page));
        search.page(page.getId());
        audit.record(user, page.getKind() == SitePage.Kind.NEWS ? "NEWS_PUBLISH" : "PAGE_PUBLISH", siteOf(page),
                Map.of("page", page.getName(), "version", page.getPublishedVersion()));
        return page;
    }

    public SitePage discardDraft(CurrentUser user, UUID pageId) {
        SitePage page = editable(user, pageId);
        if (!page.isPublished()) {
            throw ApiException.conflict("never_published", "This page has never been published. Delete it instead.");
        }
        page.clearDraft();
        page.touch(user.id());
        return page;
    }

    /** Copies a published version into the draft; publishing the draft makes it current again. */
    public SitePage restore(CurrentUser user, UUID pageId, int version) {
        SitePage page = editable(user, pageId);
        SitePageVersion v = versions.findByPageIdAndVersion(page.getId(), version)
                .orElseThrow(() -> ApiException.notFound("Version"));
        page.saveDraft(v.getTitle(), v.getDescription(), v.getBannerImageId(), v.getCanvas(), user.id());
        return page;
    }

    public void delete(CurrentUser user, UUID pageId) {
        SitePage page = pages.findById(pageId).orElseThrow(() -> ApiException.notFound("Page"));
        Node site = site(user, page.getSiteId());
        visible(user, site, page);
        permissions.require(user, site, Right.DELETE_ITEMS);
        if (homePageId(site).filter(page.getId()::equals).isPresent()) setHomeSetting(site, null);
        pages.delete(page);
        search.page(page.getId());
        audit.record(user, page.getKind() == SitePage.Kind.NEWS ? "NEWS_DELETE" : "PAGE_DELETE", site,
                Map.of("page", page.getName()));
    }

    /** Uses a published page as the site's home page, or (null) goes back to the built-in home page. */
    public void setHomePage(CurrentUser user, UUID siteId, UUID pageId) {
        Node site = site(user, siteId);
        permissions.require(user, site, Right.MANAGE_WEB);
        if (pageId != null) {
            SitePage page = pages.findById(pageId).filter(p -> p.getSiteId().equals(site.getId()))
                    .orElseThrow(() -> ApiException.notFound("Page"));
            if (!page.isPublished()) throw ApiException.conflict("not_published", "Publish the page before using it as the home page");
        }
        setHomeSetting(site, pageId);
        site.touch(user.id());
        audit.record(user, "SITE_HOME_PAGE", site, pageId == null ? Map.of() : Map.of("page", pageId.toString()));
    }

    /** Stores an image for pages in the site's "Site Assets" library, creating the library on first use. */
    public Node uploadImage(CurrentUser user, UUID siteId, String fileName, Path data) {
        Node site = site(user, siteId);
        permissions.require(user, site, Right.ADD_ITEMS);
        String mime = sniff(data);
        if (mime == null) throw ApiException.badRequest("invalid_image", "Use a PNG, JPEG, GIF or WebP image");
        Node library = nodes.findLiveByPath(site.getPath() + "/" + ASSETS_LIBRARY).orElseGet(() -> {
            Node lib = Node.create(Node.Kind.LIBRARY, site, ASSETS_LIBRARY, user.id());
            lib.setTitle("Site Assets");
            lib.setDescription("Images used on the site's pages");
            LibrarySettings.DEFAULT.applyTo(lib);
            nodes.save(lib);
            audit.record(user, "LIBRARY_CREATE", lib);
            return lib;
        });
        String base = fileName == null || fileName.isBlank() ? "image" : Path.of(fileName.replace('\\', '/')).getFileName().toString();
        String name = base;
        for (int i = 2; nodes.findLiveByPath(library.getPath() + "/" + name).isPresent(); i++) {
            int dot = base.lastIndexOf('.');
            name = dot > 0 ? base.substring(0, dot) + "-" + i + base.substring(dot) : base + "-" + i;
        }
        return documents.upload(user, library.getId(), name, mime, data, false, null);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private SitePage visible(CurrentUser user, Node site, SitePage page) {
        if (!page.isPublished() && !can(user, site, Right.EDIT_ITEMS)) throw ApiException.notFound("Page");
        return page;
    }

    private SitePage editable(CurrentUser user, UUID pageId) {
        SitePage page = pages.findById(pageId).orElseThrow(() -> ApiException.notFound("Page"));
        Node site = site(user, page.getSiteId());
        visible(user, site, page);
        permissions.require(user, site, Right.EDIT_ITEMS);
        return page;
    }

    private Node siteOf(SitePage page) {
        return nodes.findLive(page.getSiteId()).orElseThrow(() -> ApiException.notFound("Site"));
    }

    private void sameSite(SitePage page, UUID ref, Node.Kind kind) {
        Node n = nodes.findLive(ref).orElse(null);
        if (n == null || !page.getSiteId().equals(n.getSiteId()) || (kind != null && n.getKind() != kind)) {
            throw ApiException.badRequest("invalid_page", "Web parts can only show content from this site");
        }
    }

    private void setHomeSetting(Node site, UUID pageId) {
        Map<String, Object> settings = new HashMap<>(site.getSettings());
        if (pageId == null) settings.remove(HOME_SETTING);
        else settings.put(HOME_SETTING, pageId.toString());
        site.setSettings(settings);
        nodes.save(site);
    }

    private static String title(String raw) {
        String t = raw == null ? "" : raw.strip();
        if (t.isEmpty()) throw ApiException.badRequest("invalid_page", "Give the page a title");
        if (t.length() > 255) throw ApiException.badRequest("invalid_page", "The title is too long (255 characters)");
        return t;
    }

    /** URL name from the title: "Q3 Town Hall!" → "q3-town-hall", made unique within the site. */
    private String uniqueName(Node site, String title) {
        String ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String base = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.length() > 80) base = base.substring(0, 80).replaceAll("-+$", "");
        if (base.isEmpty()) base = "page";
        String name = base;
        for (int i = 2; pages.existsBySiteIdAndName(site.getId(), name); i++) name = base + "-" + i;
        return name;
    }

    private static Canvas starter(SitePage.Kind kind) {
        // pages and news posts both start with one empty text block
        Canvas.WebPart text = new Canvas.WebPart("text-1", "text", Map.of("html", ""));
        return new Canvas(List.of(new Canvas.Section("one", "none", List.of(List.of(text))))).validated();
    }

    private Canvas canvas(Map<String, Object> raw) {
        if (raw == null) return Canvas.empty();
        return json.convertValue(raw, Canvas.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Canvas c) {
        return json.convertValue(c, Map.class);
    }

    private static String sniff(Path data) {
        try {
            if (Files.size(data) > MAX_IMAGE_BYTES) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "image_too_large", "Images are limited to 10 MB");
            }
            try (InputStream in = Files.newInputStream(data)) {
                return PortalService.imageType(in.readNBytes(16));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
