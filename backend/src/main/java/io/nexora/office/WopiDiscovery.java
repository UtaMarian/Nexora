package io.nexora.office;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Reads the editor's WOPI discovery document ({@code /hosting/discovery}) to learn which file extensions it
 * can edit or view and the URL to open each one. Cached for an hour; failures are retried after a minute.
 */
@Service
public class WopiDiscovery {
    private static final Logger log = LoggerFactory.getLogger(WopiDiscovery.class);

    private final OfficeProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private volatile Map<String, Map<String, String>> actions = Map.of(); // ext -> action name -> urlsrc
    private volatile Instant nextRefresh = Instant.EPOCH;

    public WopiDiscovery(OfficeProperties props) {
        this.props = props;
    }

    public boolean available() {
        return props.enabled() && !actions().isEmpty();
    }

    /** Extensions the editor can open with the given action ("edit" or "view"). */
    public Set<String> extensions(String action) {
        Set<String> out = new TreeSet<>();
        actions().forEach((ext, acts) -> {
            if (acts.containsKey(action)) out.add(ext);
        });
        return out;
    }

    /** The editor URL for an extension and action, without the WOPISrc parameter, or empty if unsupported. */
    public Optional<String> urlFor(String ext, String action) {
        Map<String, String> acts = actions().get(ext.toLowerCase(Locale.ROOT));
        if (acts == null) return Optional.empty();
        String url = acts.get(action);
        if (url == null && "edit".equals(action)) return Optional.empty();
        return Optional.ofNullable(url != null ? url : acts.get("view"));
    }

    private Map<String, Map<String, String>> actions() {
        if (props.enabled() && Instant.now().isAfter(nextRefresh)) refresh();
        return actions;
    }

    private synchronized void refresh() {
        if (Instant.now().isBefore(nextRefresh)) return;
        try {
            HttpResponse<InputStream> res = http.send(
                    HttpRequest.newBuilder(URI.create(props.discoveryUrl())).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
            try (InputStream in = res.body()) {
                actions = parse(in);
            }
            nextRefresh = Instant.now().plus(Duration.ofHours(1));
            log.info("Office editor discovered at {}: {} editable formats", props.discoveryUrl(), extensions("edit").size());
        } catch (Exception e) {
            nextRefresh = Instant.now().plus(Duration.ofMinutes(1));
            log.warn("Office editor discovery at {} failed: {}", props.discoveryUrl(), e.toString());
        }
    }

    static Map<String, Map<String, String>> parse(InputStream xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = f.newDocumentBuilder().parse(xml);
        NodeList list = doc.getElementsByTagName("action");
        Map<String, Map<String, String>> out = new HashMap<>();
        for (int i = 0; i < list.getLength(); i++) {
            Element a = (Element) list.item(i);
            String ext = a.getAttribute("ext");
            String name = a.getAttribute("name");
            String url = a.getAttribute("urlsrc");
            if (ext.isEmpty() || url.isEmpty()) continue;
            out.computeIfAbsent(ext.toLowerCase(Locale.ROOT), k -> new HashMap<>())
                    .putIfAbsent(name, stripPlaceholders(url));
        }
        return Map.copyOf(out);
    }

    /** Removes WOPI placeholder blocks like {@code <ui=UI_LLCC&>} and leaves the URL ready for parameters. */
    static String stripPlaceholders(String urlsrc) {
        String url = urlsrc.replaceAll("<[^>]*>", "");
        if (!url.contains("?")) return url + "?";
        return url.endsWith("?") || url.endsWith("&") ? url : url + "&";
    }
}
