package io.nexora.portal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.content.ContentService;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.identity.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Hub sites tie sites together: a shared navigation bar and theme, and news rolled up from everything in the hub. Only
 * farm administrators make a site a hub; the owners of any site can then join one. Membership is stored as the hub's
 * path, so it survives export. A subsite follows its parent unless it joins a hub itself.
 */
@Service
@Transactional
public class HubService {
    private static final String HUB = "hub";
    private static final String HUB_PATH = "hubPath";
    private static final int MAX_NAVIGATION = 12;

    public record Hub(UUID id, String title, String path, List<SiteDesign.Link> navigation, String theme) {}

    private final NodeRepository nodes;
    private final ContentService content;
    private final PermissionService permissions;
    private final AuditService audit;
    private final ObjectMapper json;

    public HubService(NodeRepository nodes, ContentService content, PermissionService permissions, AuditService audit, ObjectMapper json) {
        this.nodes = nodes;
        this.content = content;
        this.permissions = permissions;
        this.audit = audit;
        this.json = json;
    }

    public static boolean isHub(Node site) {
        return site.getSettings().get(HUB) instanceof Map;
    }

    /** Whether a site joined a hub itself, as opposed to following its parent. */
    public static Optional<String> joinedHubPath(Node site) {
        return site.getSettings().get(HUB_PATH) instanceof String s ? Optional.of(s) : Optional.empty();
    }

    /** The hub this site is part of: itself, the hub it joined, or its parent's. */
    @Transactional(readOnly = true)
    public Optional<Hub> hubOf(Node site) {
        for (Node s = site; s != null && s.getKind() == Node.Kind.SITE; s = s.getParentId() == null ? null : nodes.findLive(s.getParentId()).orElse(null)) {
            if (isHub(s)) return Optional.of(hub(s));
            Optional<String> path = joinedHubPath(s);
            if (path.isPresent()) return nodes.findLiveByPath(path.get()).filter(HubService::isHub).map(this::hub);
        }
        return Optional.empty();
    }

    private Hub hub(Node site) {
        Map<?, ?> h = (Map<?, ?>) site.getSettings().get(HUB);
        String title = h.get("title") instanceof String t && !t.isBlank() ? t : site.getTitle();
        List<SiteDesign.Link> navigation = h.get("navigation") == null ? List.of()
                : json.convertValue(h.get("navigation"), new TypeReference<List<SiteDesign.Link>>() {});
        String theme = site.getSettings().get("design") instanceof Map<?, ?> d && d.get("theme") instanceof String t ? t : null;
        return new Hub(site.getId(), title, site.getPath(), navigation, theme);
    }

    /** Hubs someone can join: those they can see. */
    @Transactional(readOnly = true)
    public List<Hub> visibleHubs(CurrentUser user) {
        return permissions.filterVisible(user, nodes.findLiveSites().stream().filter(HubService::isHub).toList()).stream().map(this::hub).toList();
    }

    /** Every site in this site's hub, the hub itself included, that the user can see. Only this site if it has no hub. */
    @Transactional(readOnly = true)
    public List<Node> sitesInHub(CurrentUser user, Node site) {
        Optional<Hub> hub = hubOf(site);
        if (hub.isEmpty()) return List.of(site);
        UUID id = hub.get().id();
        List<Node> members = nodes.findLiveSites().stream().filter(s -> hubOf(s).filter(h -> h.id().equals(id)).isPresent()).toList();
        return permissions.filterVisible(user, members);
    }

    public void register(CurrentUser user, UUID siteId, boolean enabled) {
        Node site = site(user, siteId);
        Map<String, Object> settings = new HashMap<>(site.getSettings());
        if (enabled) {
            if (site.getParentId() != null) throw ApiException.badRequest("is_a_subsite", "A subsite cannot be a hub");
            if (!isHub(site)) settings.put(HUB, new HashMap<>(Map.of("title", String.valueOf(site.getTitle()), "navigation", List.of())));
            settings.remove(HUB_PATH); // a hub does not belong to another hub
        } else {
            settings.remove(HUB);
        }
        site.setSettings(settings);
        audit.record(user, enabled ? "HUB_REGISTER" : "HUB_UNREGISTER", site);
    }

    /** Changes a hub's bar. For the owners of the hub site. */
    public Hub update(CurrentUser user, UUID siteId, String title, List<SiteDesign.Link> navigation) {
        Node site = site(user, siteId);
        permissions.require(user, site, Right.MANAGE_WEB);
        if (!isHub(site)) throw ApiException.badRequest("not_a_hub", "This site is not a hub");
        String t = title == null ? "" : title.strip();
        if (t.isEmpty() || t.length() > 120) throw ApiException.badRequest("invalid_hub", "A hub needs a name of at most 120 characters");
        List<SiteDesign.Link> links = SiteDesign.links(navigation == null ? List.of() : navigation, MAX_NAVIGATION, "Hub navigation");
        Map<String, Object> hub = new HashMap<>();
        hub.put("title", t);
        hub.put("navigation", json.convertValue(links, new TypeReference<List<Map<String, Object>>>() {}));
        Map<String, Object> settings = new HashMap<>(site.getSettings());
        settings.put(HUB, hub);
        site.setSettings(settings);
        audit.record(user, "HUB_UPDATE", site, Map.of("title", t));
        return hub(site);
    }

    /** Joins a hub, or leaves one with {@code hubId} null. For the owners of the site. */
    public void join(CurrentUser user, UUID siteId, UUID hubId) {
        Node site = site(user, siteId);
        permissions.require(user, site, Right.MANAGE_WEB);
        if (site.getParentId() != null) throw ApiException.badRequest("is_a_subsite", "A subsite follows the hub of its parent site");
        Map<String, Object> settings = new HashMap<>(site.getSettings());
        if (hubId == null) {
            settings.remove(HUB_PATH);
        } else {
            if (isHub(site)) throw ApiException.badRequest("is_a_hub", "A hub cannot join another hub");
            Node hub = content.get(user, hubId);
            if (!isHub(hub)) throw ApiException.badRequest("not_a_hub", "That site is not a hub");
            settings.put(HUB_PATH, hub.getPath());
        }
        site.setSettings(settings);
        audit.record(user, hubId == null ? "HUB_LEAVE" : "HUB_JOIN", site);
    }

    private Node site(CurrentUser user, UUID id) {
        Node site = content.get(user, id);
        if (site.getKind() != Node.Kind.SITE) throw ApiException.badRequest("not_a_site", "Not a site");
        return site;
    }
}
