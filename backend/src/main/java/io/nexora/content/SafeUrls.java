package io.nexora.content;

import java.net.URI;
import java.util.Locale;

/** Accepts only links that are safe to render: http(s), mailto, or a server-relative path. */
public final class SafeUrls {
    private SafeUrls() {}

    public static boolean isSafe(String url) {
        if (url == null || url.isBlank() || url.length() > 2048) return false;
        String u = url.strip();
        if (u.startsWith("//")) return false;              // protocol-relative: could point anywhere
        if (u.startsWith("/")) return !u.contains("\\");   // server-relative, e.g. /sites/hr/Shared Documents
        try {
            URI uri = new URI(u.replace(" ", "%20"));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return switch (scheme) {
                case "http", "https" -> uri.getHost() != null;
                case "mailto" -> true;
                default -> false;
            };
        } catch (Exception e) {
            return false;
        }
    }
}
