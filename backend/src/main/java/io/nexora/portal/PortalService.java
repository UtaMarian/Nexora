package io.nexora.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.content.ContentService;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.identity.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Site creation from templates, and site branding ("Change the look"). */
@Service
@Transactional
public class PortalService {
    public static final int MAX_LOGO_BYTES = 512 * 1024;
    private static final String LOGO = "logo";

    private final ContentService content;
    private final NodeRepository nodes;
    private final PermissionService permissions;
    private final SiteAssetRepository assets;
    private final AuditService audit;
    private final ObjectMapper json;

    public PortalService(ContentService content, NodeRepository nodes, PermissionService permissions,
                         SiteAssetRepository assets, AuditService audit, ObjectMapper json) {
        this.content = content;
        this.nodes = nodes;
        this.permissions = permissions;
        this.assets = assets;
        this.audit = audit;
        this.json = json;
    }

    // ---- creation ------------------------------------------------------------------------------

    public Node createSite(CurrentUser user, String slug, String title, String description, SiteTemplate template) {
        return apply(user, content.createSite(user, slug, title, description), description, template);
    }

    public Node createSubsite(CurrentUser user, UUID parentId, String slug, String title, String description, SiteTemplate template,
                              boolean uniquePermissions) {
        return apply(user, content.createSubsite(user, parentId, slug, title, description, uniquePermissions), description, template);
    }

    private Node apply(CurrentUser user, Node site, String description, SiteTemplate template) {
        SiteTemplate t = template == null ? SiteTemplate.TEAM : template;
        Map<String, Node> byTitle = new LinkedHashMap<>();
        nodes.findLiveByPath(site.getPath() + "/" + ContentService.DEFAULT_LIBRARY).ifPresent(d -> byTitle.put("Documents", d));
        for (SiteTemplate.LibrarySpec l : t.libraries) {
            byTitle.put(l.title(), content.createLibrary(user, site.getId(), l.name(), l.title(), null));
        }
        UUID newsList = null;
        for (SiteTemplate.ListSpec l : t.lists) {
            Node list = content.createList(user, site.getId(), l.name(), l.title(), null, l.template());
            byTitle.put(l.title(), list);
            if (t.news && newsList == null && l.template() == io.nexora.content.ListTemplate.ANNOUNCEMENTS) newsList = list.getId();
        }

        List<SiteDesign.Link> nav = new ArrayList<>();
        nav.add(new SiteDesign.Link("Home", site.getPath()));
        for (String navTitle : t.navigation) {
            Node target = byTitle.get(navTitle);
            if (target != null) nav.add(new SiteDesign.Link(navTitle, target.getPath()));
        }
        List<SiteDesign.Link> quick = nav.subList(1, nav.size());
        SiteDesign design = new SiteDesign(t.theme,
                new SiteDesign.Header(t.headerLayout, t.headerBackground, true, true),
                nav,
                new SiteDesign.Home("Welcome to " + site.getTitle(),
                        description == null ? "" : description, new ArrayList<>(quick), t.news, newsList, true, true));
        store(site, design.validated(SiteDesign.defaults(site.getTitle(), site.getPath())), t);
        audit.record(user, "SITE_TEMPLATE_APPLIED", site, Map.of("template", t.name()));
        return site;
    }

    // ---- design --------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SiteDesign design(CurrentUser user, UUID siteId) {
        return read(site(user, siteId, Right.VIEW));
    }

    public SiteDesign updateDesign(CurrentUser user, UUID siteId, SiteDesign requested) {
        Node site = site(user, siteId, Right.MANAGE_WEB);
        SiteDesign current = read(site);
        SiteDesign next = requested.validated(current);
        UUID newsList = next.home().newsListId();
        if (newsList != null) {
            Node list = nodes.findLive(newsList).orElse(null);
            if (list == null || list.getKind() != Node.Kind.LIST || !list.getSiteId().equals(site.getId())) {
                throw ApiException.badRequest("invalid_design", "The news source must be a list in this site");
            }
        }
        store(site, next, null);
        site.touch(user.id());
        audit.record(user, "SITE_DESIGN_UPDATE", site, Map.of("theme", next.theme(), "layout", next.header().layout()));
        return next;
    }

    @Transactional(readOnly = true)
    public Optional<String> template(Node site) {
        Object t = site.getSettings().get("template");
        return Optional.ofNullable(t == null ? null : t.toString());
    }

    private SiteDesign read(Node site) {
        SiteDesign defaults = SiteDesign.defaults(site.getTitle(), site.getPath());
        Object raw = site.getSettings().get("design");
        if (raw == null) return defaults;
        try {
            return json.convertValue(raw, SiteDesign.class).validated(defaults);
        } catch (RuntimeException e) {
            return defaults;   // never let a bad stored design break the site
        }
    }

    @SuppressWarnings("unchecked")
    private void store(Node site, SiteDesign design, SiteTemplate template) {
        Map<String, Object> settings = new HashMap<>(site.getSettings());
        settings.put("design", json.convertValue(design, Map.class));
        if (template != null) settings.put("template", template.name());
        site.setSettings(settings);
        nodes.save(site);
    }

    // ---- logo ----------------------------------------------------------------------------------

    public record Logo(String mimeType, String sha256, byte[] data) {}

    public Logo setLogo(CurrentUser user, UUID siteId, byte[] data) {
        Node site = site(user, siteId, Right.MANAGE_WEB);
        if (data.length == 0) throw ApiException.badRequest("invalid_logo", "The image is empty");
        if (data.length > MAX_LOGO_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "logo_too_large", "Logos are limited to 512 KB");
        }
        String mime = imageType(data);
        if (mime == null) throw ApiException.badRequest("invalid_logo", "Use a PNG, JPEG, GIF or WebP image");
        SiteAsset asset = assets.findBySiteIdAndKind(site.getId(), LOGO).orElseGet(() -> new SiteAsset(site.getId(), LOGO));
        asset.replace(mime, sha256(data), data);
        assets.save(asset);
        audit.record(user, "SITE_LOGO_UPDATE", site, Map.of("size", data.length));
        return new Logo(mime, asset.getSha256(), data);
    }

    @Transactional(readOnly = true)
    public Optional<Logo> logo(CurrentUser user, UUID siteId) {
        Node site = site(user, siteId, Right.VIEW);
        return assets.findBySiteIdAndKind(site.getId(), LOGO).map(a -> new Logo(a.getMimeType(), a.getSha256(), a.getData()));
    }

    @Transactional(readOnly = true)
    public Optional<String> logoVersion(Node site) {
        return assets.findBySiteIdAndKind(site.getId(), LOGO).map(a -> a.getSha256().substring(0, 12));
    }

    public void deleteLogo(CurrentUser user, UUID siteId) {
        Node site = site(user, siteId, Right.MANAGE_WEB);
        assets.findBySiteIdAndKind(site.getId(), LOGO).ifPresent(assets::delete);
        audit.record(user, "SITE_LOGO_DELETE", site);
    }

    /** Sniffs the image type from its first bytes; the uploaded name and content type are not trusted. */
    public static String imageType(byte[] d) {
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (d.length >= 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') return "image/gif";
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "image/webp";
        return null;
    }

    private Node site(CurrentUser user, UUID siteId, Right right) {
        Node site = content.get(user, siteId);
        if (site.getKind() != Node.Kind.SITE) throw ApiException.badRequest("not_a_site", "Not a site");
        permissions.require(user, site, right);
        return site;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
