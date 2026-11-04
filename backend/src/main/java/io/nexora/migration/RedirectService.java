package io.nexora.migration;

import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an old SharePoint link into the place its content lives now, using the map built during migration.
 * Handles direct file and folder URLs, library and list views (Forms/AllItems.aspx), item forms
 * (DispForm.aspx?ID=3), Office Online links (Doc.aspx?sourcedoc={guid}) and "/:w:/r/..." sharing links.
 */
@Service
public class RedirectService {
    private static final Pattern URL = Pattern.compile("^(?:[a-z][a-z0-9+.-]*://([^/?#]+))?([^?#]*)(?:\\?([^#]*))?.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHARING = Pattern.compile("^/:[a-z]:/r(/.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

    private final MigrationItemRepository items;
    private final MigrationTaskRepository tasks;
    private final MigrationSourceRepository sources;
    private final NodeRepository nodes;

    public RedirectService(MigrationItemRepository items, MigrationTaskRepository tasks,
                           MigrationSourceRepository sources, NodeRepository nodes) {
        this.items = items;
        this.tasks = tasks;
        this.sources = sources;
        this.nodes = nodes;
    }

    /**
     * The node an old link maps to. The same SharePoint URL can have been migrated more than once, so this
     * returns the first copy {@code visible} accepts: never content the caller may not see.
     */
    @Transactional(readOnly = true)
    public Optional<Node> resolve(String url, Predicate<Node> visible) {
        if (url == null || url.isBlank()) return Optional.empty();
        Matcher m = URL.matcher(url.strip());
        if (!m.matches()) return Optional.empty();
        String host = m.group(1);
        String path = decode(m.group(2));
        Map<String, String> params = params(m.group(3));

        String doc = params.get("sourcedoc");
        if (doc != null) {
            Matcher g = GUID.matcher(doc);
            if (g.find()) {
                Optional<Node> n = first(items.findBySourceKey(g.group()), host, visible);
                if (n.isPresent()) return n;
            }
        }
        Matcher s = SHARING.matcher(path);
        if (s.matches()) path = s.group(1);
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

        String last = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
        if ((last.equals("dispform.aspx") || last.equals("editform.aspx")) && params.containsKey("id")) {
            for (MigrationItem list : prefer(items.findBySourceUrl(parent), host)) {
                Optional<Node> n = items.findByTaskIdAndSourceKey(list.getTaskId(), list.getSourceKey() + ":" + params.get("id"))
                        .flatMap(i -> nodes.findLive(i.getNodeId())).filter(visible);
                if (n.isPresent()) return n;
            }
        }
        if (parent.toLowerCase(Locale.ROOT).endsWith("/forms") && last.endsWith(".aspx")) {
            path = parent.substring(0, parent.length() - "/forms".length());   // a library view
        }

        // the object itself, or else its closest migrated parent (keeping the rest of the path if it exists)
        Node fallback = null;
        for (String p = path; p.length() > 1; p = p.substring(0, Math.max(p.lastIndexOf('/'), 0))) {
            Optional<Node> mapped = first(items.findBySourceUrl(p), host, visible);
            if (mapped.isPresent()) {
                if (p.length() == path.length()) return mapped;
                Optional<Node> below = nodes.findLiveByPath(mapped.get().getPath() + path.substring(p.length())).filter(visible);
                if (below.isPresent()) return below;
                if (fallback == null) fallback = mapped.get();
            }
        }
        return Optional.ofNullable(fallback).or(() -> nodes.findLiveByPath(decode(m.group(2))).filter(visible));
    }

    private Optional<Node> first(List<MigrationItem> found, String host, Predicate<Node> visible) {
        for (MigrationItem i : prefer(found, host)) {
            Optional<Node> n = nodes.findLive(i.getNodeId()).filter(visible);
            if (n.isPresent()) return n;
        }
        return Optional.empty();
    }

    /** Two farms can both have /sites/hr: a link naming a host prefers the task for that farm. */
    private List<MigrationItem> prefer(List<MigrationItem> found, String host) {
        if (host == null || found.size() < 2) return found;
        List<MigrationItem> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparing(i -> !fromHost(i, host)));
        return sorted;
    }

    private boolean fromHost(MigrationItem i, String host) {
        return tasks.findById(i.getTaskId()).flatMap(t -> sources.findById(t.getSourceId()))
                .map(s -> s.getBaseUrl().toLowerCase(Locale.ROOT).contains("://" + host.toLowerCase(Locale.ROOT)))
                .orElse(false);
    }

    private static Map<String, String> params(String query) {
        Map<String, String> out = new HashMap<>();
        if (query == null) return out;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) out.put(kv.substring(0, eq).toLowerCase(Locale.ROOT), decode(kv.substring(eq + 1)));
        }
        return out;
    }

    private static String decode(String s) {
        if (s == null) return "";
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
