package io.nexora.search;

import io.nexora.acl.PermissionLevel;
import io.nexora.acl.PermissionService;
import io.nexora.content.ContentService;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.pages.PageService;
import io.nexora.pages.SitePage;
import io.nexora.pages.SitePageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Security-trimmed search. Access is enforced twice. The engine only matches documents governed by an ACL the caller
 * is on, which keeps counts and facets honest; then every hit is checked against the database through
 * {@link PermissionService}, so nothing the index has not caught up with yet can reach a person. Result details always
 * come from the database too: the index decides what matches, never what is shown.
 */
@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int SNIPPET_LENGTH = 240;
    /** The engines' limit on from + size. */
    private static final int MAX_WINDOW = 10_000;

    /** A piece of a snippet. {@code match} marks the words to highlight. */
    public record Segment(String text, boolean match) {}

    public record Hit(UUID id, String kind, String title, String name, String path, String siteTitle,
                      List<Segment> snippet, Instant modifiedAt, String modifiedBy, Long sizeBytes, String mimeType) {}

    public record FacetValue(String value, String label, long count) {}

    /** @param degraded the engine is down, so these are file and folder name matches only */
    public record Response(long total, List<Hit> hits, Map<String, List<FacetValue>> facets, boolean degraded) {}

    private final SearchEngine engine;
    private final SearchSwitch searchSwitch;
    private final PermissionService permissions;
    private final ContentService content;
    private final NodeRepository nodes;
    private final SitePageRepository pages;
    private final PageService pageService;
    private final PrincipalRepository principals;

    public SearchService(SearchEngine engine, SearchSwitch searchSwitch, PermissionService permissions,
                         ContentService content, NodeRepository nodes,
                         SitePageRepository pages, PageService pageService, PrincipalRepository principals) {
        this.engine = engine;
        this.searchSwitch = searchSwitch;
        this.permissions = permissions;
        this.content = content;
        this.nodes = nodes;
        this.pages = pages;
        this.pageService = pageService;
        this.principals = principals;
    }

    public Response search(CurrentUser user, String q, SearchQuery.Filters filters, SearchQuery.Sort sort, int page, int size) {
        searchSwitch.requireEnabled();
        int from = Math.min(Math.max(page, 0) * size, MAX_WINDOW - size);
        SearchQuery query = SearchQuery.parse(q, filters, sort, from, size);
        if (query.isEmpty()) return new Response(0, List.of(), Map.of(), false);

        Set<UUID> sources = user.admin() ? null : permissions.readableSources(user);
        if (sources != null && sources.isEmpty()) return new Response(0, List.of(), Map.of(), false);
        SearchEngine.Results results;
        try {
            results = engine.search(query, sources);
        } catch (SearchEngine.UnavailableException e) {
            log.warn("Search is falling back to names: {}", e.getMessage());
            return byName(user, query.text(), size);
        }

        List<Hit> hits = hydrate(user, results.hits());
        long dropped = results.hits().size() - hits.size();
        return new Response(results.total() - dropped, hits, labelled(user, results.facets()), false);
    }

    private Response byName(CurrentUser user, String text, int size) {
        if (text == null) return new Response(0, List.of(), Map.of(), true);
        String plain = text.replaceAll("[\"*+|]", " ").strip();
        List<SearchEngine.Hit> found = content.search(user, plain, size).stream()
                .map(n -> new SearchEngine.Hit(n.getId(), null)).toList();
        List<Hit> hits = hydrate(user, found);
        return new Response(hits.size(), hits, Map.of(), true);
    }

    private List<Hit> hydrate(CurrentUser user, List<SearchEngine.Hit> hits) {
        List<UUID> ids = hits.stream().map(SearchEngine.Hit::id).toList();
        Map<UUID, Node> found = nodes.findAllById(ids).stream()
                .filter(n -> !n.isDeleted()).collect(Collectors.toMap(Node::getId, Function.identity()));
        Map<UUID, SitePage> foundPages = pages.findAllById(ids).stream()
                .filter(SitePage::isPublished).collect(Collectors.toMap(SitePage::getId, Function.identity()));

        Set<UUID> siteIds = new HashSet<>();
        found.values().forEach(n -> siteIds.add(n.getSiteId()));
        foundPages.values().forEach(p -> siteIds.add(p.getSiteId()));
        Map<UUID, Node> sites = nodes.findAllById(siteIds).stream()
                .filter(n -> !n.isDeleted()).collect(Collectors.toMap(Node::getId, Function.identity()));

        List<Node> toCheck = new ArrayList<>(found.values());
        toCheck.addAll(sites.values());
        Map<UUID, PermissionLevel> levels = permissions.levels(user, toCheck);

        Set<UUID> people = new HashSet<>();
        found.values().forEach(n -> people.add(n.getModifiedBy()));
        foundPages.values().forEach(p -> people.add(p.getPublishedBy() != null ? p.getPublishedBy() : p.getModifiedBy()));
        people.remove(null);
        Map<UUID, String> names = principals.findAllById(people).stream()
                .collect(Collectors.toMap(Principal::getId, Principal::getDisplayName));

        List<Hit> out = new ArrayList<>();
        for (SearchEngine.Hit h : hits) {
            List<Segment> snippet = segments(h.snippet());
            Node n = found.get(h.id());
            if (n != null) {
                if (!levels.containsKey(n.getId())) continue;
                Node site = sites.get(n.getSiteId());
                out.add(new Hit(n.getId(), n.getKind().name(), n.getTitle() != null ? n.getTitle() : n.getName(),
                        n.getName(), n.getPath(), site == null ? null : site.getTitle(), snippet, n.getModifiedAt(),
                        names.get(n.getModifiedBy()), n.getSizeBytes(), n.getMimeType()));
                continue;
            }
            SitePage p = foundPages.get(h.id());
            if (p == null) continue;
            Node site = sites.get(p.getSiteId());
            if (site == null || !levels.containsKey(site.getId())) continue;
            UUID by = p.getPublishedBy() != null ? p.getPublishedBy() : p.getModifiedBy();
            out.add(new Hit(p.getId(), p.getKind().name(), p.getTitle(),
                    p.getName(), pageService.url(site, p), site.getTitle(), snippet,
                    p.getPublishedAt() != null ? p.getPublishedAt() : p.getModifiedAt(), names.get(by), null, null));
        }
        return out;
    }

    private static String type(Node n) {
        return n.getKind().name().toLowerCase(Locale.ROOT);
    }

    private Map<String, List<FacetValue>> labelled(CurrentUser user, Map<String, List<SearchEngine.Facet>> facets) {
        Map<String, String> siteTitles = new HashMap<>();
        for (Node s : content.visibleSites(user)) siteTitles.put(s.getName().toLowerCase(Locale.ROOT), s.getTitle());
        Map<String, List<FacetValue>> out = new LinkedHashMap<>();
        facets.forEach((name, values) -> out.put(name, values.stream()
                .map(f -> new FacetValue(f.value(), name.equals(SearchEngine.FACET_SITE)
                        ? siteTitles.getOrDefault(f.value(), f.value()) : f.value(), f.count()))
                .toList()));
        return out;
    }

    /** Splits a marked-up snippet, clipped around its first match, so clients never have to render HTML. */
    static List<Segment> segments(String snippet) {
        if (snippet == null) return List.of();
        String s = snippet.strip();
        int first = s.indexOf(SearchEngine.MATCH_START);
        int start = first < 0 ? 0 : Math.max(0, first - SNIPPET_LENGTH / 3);
        if (start > 0) {
            int space = s.indexOf(' ', start);
            if (space > 0 && space < first) start = space + 1;
        }
        int end = Math.min(s.length(), start + SNIPPET_LENGTH);
        if (end < s.length()) {
            int space = s.lastIndexOf(' ', end);
            if (space > first) end = space;
        }

        List<Segment> out = new ArrayList<>();
        if (start > 0) out.add(new Segment("… ", false));
        boolean match = false;
        StringBuilder b = new StringBuilder();
        for (int i = start; i < end; i++) {
            String c = s.substring(i, i + 1);
            if (c.equals(SearchEngine.MATCH_START) || c.equals(SearchEngine.MATCH_END)) {
                if (!b.isEmpty()) out.add(new Segment(b.toString(), match));
                b.setLength(0);
                match = c.equals(SearchEngine.MATCH_START);
            } else {
                b.append(c);
            }
        }
        if (!b.isEmpty()) out.add(new Segment(b.toString(), match));
        if (end < s.length()) out.add(new Segment(" …", false));
        return out;
    }
}
