package io.nexora.content;

import io.nexora.common.ApiException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Name rules, aligned with what SharePoint accepts so migrated content keeps its names. */
public final class Names {
    private Names() {}

    private static final Pattern ILLEGAL = Pattern.compile("[\"*:<>?/\\\\|\\x00-\\x1F]");
    private static final Pattern SITE_SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
    private static final Set<String> RESERVED = Set.of(
            "con", "prn", "aux", "nul", "com1", "com2", "com3", "lpt1", "lpt2", "lpt3", "_vti_", "forms", "desktop.ini");

    public static String item(String raw) {
        if (raw == null) throw ApiException.badRequest("invalid_name", "Name is required");
        String name = raw.strip();
        if (name.isEmpty()) throw ApiException.badRequest("invalid_name", "Name is required");
        if (name.length() > 255) throw ApiException.badRequest("invalid_name", "Name is longer than 255 characters");
        if (ILLEGAL.matcher(name).find()) {
            throw ApiException.badRequest("invalid_name", "Name cannot contain \" * : < > ? / \\ |");
        }
        if (name.endsWith(".") || name.startsWith("~$")) {
            throw ApiException.badRequest("invalid_name", "Name cannot end with '.' or start with '~$'");
        }
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("invalid_name", "'" + name + "' is a reserved name");
        }
        return name;
    }

    public static String siteSlug(String raw) {
        String slug = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (!SITE_SLUG.matcher(slug).matches()) {
            throw ApiException.badRequest("invalid_site_url",
                    "Site URL must be 1-63 lowercase letters, digits or hyphens, starting with a letter or digit");
        }
        return slug;
    }

    /** Derives a stable internal column name from a display name, e.g. "Review date" → "ReviewDate". */
    public static String columnInternalName(String displayName) {
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char c : displayName.strip().toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        if (sb.isEmpty() || !Character.isLetter(sb.charAt(0))) sb.insert(0, "Col");
        return sb.length() > 64 ? sb.substring(0, 64) : sb.toString();
    }
}
