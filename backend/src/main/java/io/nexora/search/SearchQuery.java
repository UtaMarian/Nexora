package io.nexora.search;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed search. {@code text} uses the simple query syntax that OpenSearch and Lucene share, which cannot fail
 * on bad input: words must all match, {@code "exact phrases"}, {@code -exclude}, {@code a | b}, {@code prefix*}.
 *
 * @param text          the query, or null to match everything the filters allow
 * @param nameFragment  a single bare word, also matched anywhere inside file names ("2024" in "budget2024.xlsx")
 * @param authorText    words from {@code author:...}, unlike {@code authors}, which are exact names from a facet
 */
public record SearchQuery(String text, String nameFragment, Set<String> types, Set<String> extensions,
                          Set<String> sites, Set<String> authors, String authorText, Instant modifiedAfter,
                          Sort sort, int from, int size) {

    public enum Sort { RELEVANCE, MODIFIED }

    /** The "modified" facet's buckets, smallest first. */
    public static final Map<String, Duration> MODIFIED_RANGES = orderedRanges();

    private static final Pattern PROPERTY =
            Pattern.compile("(?i)(?<=^|\\s)(author|filetype|site|type):(?:\"([^\"]*)\"|(\\S+))");
    private static final Pattern BARE_WORD = Pattern.compile("[\\p{L}\\p{N}_.\\-]{3,}");

    private static Map<String, Duration> orderedRanges() {
        Map<String, Duration> m = new LinkedHashMap<>();
        m.put("day", Duration.ofDays(1));
        m.put("week", Duration.ofDays(7));
        m.put("month", Duration.ofDays(30));
        m.put("year", Duration.ofDays(365));
        return Collections.unmodifiableMap(m);
    }

    /** Filters chosen from the facets on the results page. */
    public record Filters(List<String> types, List<String> sites, List<String> authors, String modified) {
        public static final Filters NONE = new Filters(List.of(), List.of(), List.of(), null);
    }

    public static SearchQuery parse(String raw, Filters filters, Sort sort, int from, int size) {
        Set<String> types = lower(filters.types());
        Set<String> sites = lower(filters.sites());
        Set<String> authors = new LinkedHashSet<>(filters.authors());
        Set<String> extensions = new LinkedHashSet<>();
        List<String> authorWords = new ArrayList<>();

        StringBuilder rest = new StringBuilder();
        String input = raw == null ? "" : raw;
        Matcher m = PROPERTY.matcher(input);
        int at = 0;
        while (m.find()) {
            rest.append(input, at, m.start()).append(' ');
            at = m.end();
            String value = (m.group(2) != null ? m.group(2) : m.group(3)).strip();
            if (value.isEmpty()) continue;
            switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "author" -> authorWords.add(value);
                case "filetype" -> extensions.add(value.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT));
                case "site" -> sites.add(value.toLowerCase(Locale.ROOT));
                default -> types.add(value.toLowerCase(Locale.ROOT));
            }
        }
        rest.append(input.substring(at));

        String text = operators(rest.toString().strip());
        if (text.isEmpty() || text.equals("*")) text = null;
        String fragment = text != null && BARE_WORD.matcher(text).matches() ? text.toLowerCase(Locale.ROOT) : null;

        Duration range = filters.modified() == null ? null : MODIFIED_RANGES.get(filters.modified());
        return new SearchQuery(text, fragment, types, extensions, sites, authors,
                authorWords.isEmpty() ? null : String.join(" ", authorWords),
                range == null ? null : Instant.now().minus(range), sort, from, size);
    }

    /** True when there is nothing to search for: no words and no filters. */
    public boolean isEmpty() {
        return text == null && authorText == null && modifiedAfter == null && types.isEmpty() && extensions.isEmpty()
                && sites.isEmpty() && authors.isEmpty();
    }

    /** Turns SharePoint's AND, OR and NOT into the engines' operators, leaving quoted phrases alone. */
    private static String operators(String q) {
        String[] parts = q.split("\"", -1);
        for (int i = 0; i < parts.length; i += 2) {
            parts[i] = parts[i].replaceAll("(?<=^|\\s)AND(?=\\s)", "+").replaceAll("(?<=^|\\s)OR(?=\\s)", "|")
                    .replaceAll("(?<=^|\\s)NOT\\s+", "-");
        }
        return String.join("\"", parts).strip();
    }

    private static Set<String> lower(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) out.add(v.toLowerCase(Locale.ROOT));
        return out;
    }
}
