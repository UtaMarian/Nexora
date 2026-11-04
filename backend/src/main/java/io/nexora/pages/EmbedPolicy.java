package io.nexora.pages;

import io.nexora.admin.FarmSetting;
import io.nexora.admin.FarmSettingRepository;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which outside sites a page may embed. Embedding runs someone else's page inside the intranet, so it is an allow-list
 * an administrator controls, and only over https. Nobody can paste HTML: an embed is a checked address, shown in a
 * sandboxed frame.
 */
@Component
public class EmbedPolicy {
    private static final String SETTING = "pages.embed-hosts";
    private static final List<String> DEFAULT = List.of("www.youtube-nocookie.com", "player.vimeo.com", "www.google.com");
    private static final Pattern YOUTUBE = Pattern.compile("^https://(?:www\\.)?(?:youtube\\.com/watch\\?(?:.*&)?v=|youtu\\.be/)([\\w-]{6,})");
    private static final Pattern VIMEO = Pattern.compile("^https://(?:www\\.)?vimeo\\.com/(\\d+)");

    private final FarmSettingRepository settings;

    public EmbedPolicy(FarmSettingRepository settings) {
        this.settings = settings;
    }

    public List<String> hosts() {
        return settings.findById(SETTING).map(s -> Arrays.stream(s.getValue().split(",")).filter(h -> !h.isBlank()).toList()).orElse(DEFAULT);
    }

    public void setHosts(List<String> hosts, UUID actor) {
        Set<String> clean = new LinkedHashSet<>();
        for (String h : hosts) {
            String host = h.strip().toLowerCase(Locale.ROOT);
            if (!host.matches("[a-z0-9.-]+\\.[a-z]{2,}")) throw new IllegalArgumentException("'" + h + "' is not a host name");
            clean.add(host);
        }
        settings.save(new FarmSetting(SETTING, String.join(",", clean), actor));
    }

    /** Turns a page address people copy into the address that can be embedded. */
    public static String normalize(String url) {
        String u = url == null ? "" : url.strip();
        Matcher youtube = YOUTUBE.matcher(u);
        if (youtube.find()) return "https://www.youtube-nocookie.com/embed/" + youtube.group(1);
        Matcher vimeo = VIMEO.matcher(u);
        if (vimeo.find()) return "https://player.vimeo.com/video/" + vimeo.group(1);
        return u;
    }

    /** Whether an address may be embedded. Exact host names only: a subdomain is a different site. */
    public boolean allows(String url) {
        return allows(url, new HashSet<>(hosts()));
    }

    static boolean allows(String url, Set<String> hosts) {
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && hosts.contains(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
