package io.nexora.pages;

import io.nexora.common.ApiException;

import java.util.*;
import java.util.regex.Pattern;

/**
 * A page's layout: sections stacked top to bottom, each split into columns, each column holding web parts.
 *
 * <p>Web parts reference content by id (an image file, a library, a list) and never copy it, so readers only
 * ever see what their permissions allow when the page is rendered.
 */
public record Canvas(List<Section> sections) {
    public static final int MAX_SECTIONS = 20;
    public static final int MAX_WEB_PARTS = 60;
    public static final int MAX_TEXT = 100_000;

    /** Section layouts and how many columns each has. */
    public static final Map<String, Integer> LAYOUTS = Map.of(
            "one", 1, "two", 2, "three", 3, "oneThirdLeft", 2, "oneThirdRight", 2);
    public static final Set<String> BACKGROUNDS = Set.of("none", "soft", "strong");
    public static final Set<String> TYPES = Set.of("text", "image", "documents", "list", "news");

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    public record Section(String layout, String background, List<List<WebPart>> columns) {}

    public record WebPart(String id, String type, Map<String, Object> props) {}

    public static Canvas empty() {
        return new Canvas(List.of(new Section("one", "none", List.of(List.of()))));
    }

    /** Checks structure and limits, normalises every web part's settings and sanitises rich text. */
    public Canvas validated() {
        List<Section> in = sections == null ? List.of() : sections;
        if (in.size() > MAX_SECTIONS) throw bad("A page can have at most " + MAX_SECTIONS + " sections");
        int parts = 0;
        Set<String> ids = new HashSet<>();
        List<Section> out = new ArrayList<>();
        for (Section s : in) {
            if (s == null) throw bad("Empty section");
            String layout = s.layout() == null ? "one" : s.layout();
            Integer cols = LAYOUTS.get(layout);
            if (cols == null) throw bad("Unknown section layout '" + layout + "'");
            String bg = s.background() == null ? "none" : s.background();
            if (!BACKGROUNDS.contains(bg)) throw bad("Unknown section background '" + bg + "'");
            List<List<WebPart>> given = s.columns() == null ? List.of() : s.columns();
            if (given.size() > cols) throw bad("Layout '" + layout + "' has " + cols + " column(s)");
            List<List<WebPart>> columns = new ArrayList<>();
            for (int c = 0; c < cols; c++) {
                List<WebPart> column = new ArrayList<>();
                List<WebPart> src = c < given.size() && given.get(c) != null ? given.get(c) : List.of();
                for (WebPart w : src) {
                    if (++parts > MAX_WEB_PARTS) throw bad("A page can have at most " + MAX_WEB_PARTS + " web parts");
                    WebPart v = webPart(w);
                    if (!ids.add(v.id())) throw bad("Duplicate web part id '" + v.id() + "'");
                    column.add(v);
                }
                columns.add(column);
            }
            out.add(new Section(layout, bg, columns));
        }
        if (out.isEmpty()) return empty();
        return new Canvas(out);
    }

    /** Ids of content the page points at (images, libraries, lists). Call on a validated canvas. */
    public Set<UUID> references() {
        Set<UUID> refs = new LinkedHashSet<>();
        for (Section s : sections) for (List<WebPart> col : s.columns()) for (WebPart w : col) {
            for (String key : List.of("fileId", "libraryId", "listId")) {
                if (w.props().get(key) instanceof String str) refs.add(UUID.fromString(str));
            }
        }
        return refs;
    }

    /** Plain text of the page's text web parts, for news previews. Call on a validated canvas. */
    public String plainText(int max) {
        StringBuilder b = new StringBuilder();
        for (Section s : sections) for (List<WebPart> col : s.columns()) for (WebPart w : col) {
            if (!"text".equals(w.type())) continue;
            String t = RichText.toPlainText((String) w.props().get("html"));
            if (t.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(t);
            if (b.length() >= max) return b.substring(0, max).strip() + "…";
        }
        return b.toString().strip();
    }

    private static WebPart webPart(WebPart w) {
        if (w == null) throw bad("Empty web part");
        if (w.id() == null || !ID.matcher(w.id()).matches()) {
            throw bad("Every web part needs an id (letters, digits, - and _)");
        }
        if (w.type() == null || !TYPES.contains(w.type())) throw bad("Unknown web part type '" + w.type() + "'");
        Map<String, Object> p = w.props() == null ? Map.of() : w.props();
        Map<String, Object> out = new LinkedHashMap<>();
        switch (w.type()) {
            case "text" -> out.put("html", RichText.sanitize(str(p, "html", MAX_TEXT, "Text")));
            case "image" -> {
                putId(out, p, "fileId");
                out.put("alt", str(p, "alt", 300, "Alternative text"));
                out.put("caption", str(p, "caption", 300, "Caption"));
                out.put("size", oneOf(p, "size", Set.of("small", "medium", "large", "full"), "full"));
            }
            case "documents" -> {
                putId(out, p, "libraryId");
                out.put("title", str(p, "title", 120, "Title"));
                out.put("limit", num(p, "limit", 1, 50, 8));
            }
            case "list" -> {
                putId(out, p, "listId");
                out.put("title", str(p, "title", 120, "Title"));
                out.put("limit", num(p, "limit", 1, 50, 8));
            }
            case "news" -> {
                out.put("title", str(p, "title", 120, "Title"));
                out.put("limit", num(p, "limit", 1, 20, 4));
                out.put("layout", oneOf(p, "layout", Set.of("cards", "list"), "cards"));
            }
            default -> throw new IllegalStateException(w.type());
        }
        return new WebPart(w.id(), w.type(), out);
    }

    private static void putId(Map<String, Object> out, Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            out.put(key, null);
            return;
        }
        try {
            out.put(key, UUID.fromString(v.toString()).toString());
        } catch (IllegalArgumentException e) {
            throw bad("'" + key + "' must be an id");
        }
    }

    private static String str(Map<String, Object> p, String key, int max, String what) {
        Object v = p.get(key);
        if (v == null) return "";
        if (!(v instanceof String s)) throw bad(what + " must be text");
        if (s.length() > max) throw bad(what + " is too long (" + max + " characters)");
        return s.strip();
    }

    private static int num(Map<String, Object> p, String key, int min, int max, int dflt) {
        Object v = p.get(key);
        if (v == null) return dflt;
        if (!(v instanceof Number n)) throw bad("'" + key + "' must be a number");
        return Math.max(min, Math.min(max, n.intValue()));
    }

    private static String oneOf(Map<String, Object> p, String key, Set<String> allowed, String dflt) {
        Object v = p.get(key);
        if (v == null) return dflt;
        if (!allowed.contains(v.toString())) throw bad("Unknown " + key + " '" + v + "'");
        return v.toString();
    }

    private static ApiException bad(String message) {
        return ApiException.badRequest("invalid_page", message);
    }
}
