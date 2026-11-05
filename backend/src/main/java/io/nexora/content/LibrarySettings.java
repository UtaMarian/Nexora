package io.nexora.content;

import java.util.HashMap;
import java.util.Map;

/**
 * Document-library versioning settings, stored in the library node's {@code settings} JSON.
 *
 * @param versioningEnabled    keep a history of versions (if false, uploads overwrite in place)
 * @param minorVersionsEnabled uploads create drafts (0.1, 0.2 ...) that must be published to become major
 * @param requireCheckout      files must be checked out before they can be changed
 * @param maxVersions          keep at most this many versions per file; {@code null} keeps all
 * @param draftVisibility      who can see drafts; only matters with minor versions
 */
public record LibrarySettings(boolean versioningEnabled, boolean minorVersionsEnabled,
                              boolean requireCheckout, Integer maxVersions, DraftVisibility draftVisibility) {

    /** Who can see minor versions: anyone who can read, as in SharePoint's default, or only people who can edit. */
    public enum DraftVisibility { READERS, EDITORS }

    public static final LibrarySettings DEFAULT = new LibrarySettings(true, false, false, 500, DraftVisibility.READERS);

    /** Whether people who can only read get the latest major version instead of the latest version. */
    public boolean hidesDrafts() {
        return minorVersionsEnabled && draftVisibility == DraftVisibility.EDITORS;
    }

    public static LibrarySettings of(Node library) {
        Map<String, Object> s = library.getSettings();
        return new LibrarySettings(
                bool(s, "versioningEnabled", DEFAULT.versioningEnabled),
                bool(s, "minorVersionsEnabled", DEFAULT.minorVersionsEnabled),
                bool(s, "requireCheckout", DEFAULT.requireCheckout),
                s.containsKey("maxVersions")
                        ? (s.get("maxVersions") == null ? null : ((Number) s.get("maxVersions")).intValue())
                        : DEFAULT.maxVersions,
                drafts(s.get("draftVisibility")));
    }

    /** A value nobody understands, say from a hand-made package, must not open drafts up. */
    private static DraftVisibility drafts(Object value) {
        if (value == null) return DEFAULT.draftVisibility;
        try {
            return DraftVisibility.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            return DraftVisibility.EDITORS;
        }
    }

    public void applyTo(Node library) {
        Map<String, Object> s = new HashMap<>(library.getSettings());
        s.put("versioningEnabled", versioningEnabled);
        s.put("minorVersionsEnabled", minorVersionsEnabled);
        s.put("requireCheckout", requireCheckout);
        s.put("maxVersions", maxVersions);
        s.put("draftVisibility", draftVisibility.name());
        library.setSettings(s);
    }

    private static boolean bool(Map<String, Object> s, String key, boolean dflt) {
        Object v = s.get(key);
        return v instanceof Boolean b ? b : dflt;
    }
}
