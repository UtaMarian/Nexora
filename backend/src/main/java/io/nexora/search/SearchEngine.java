package io.nexora.search;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A full-text index. It holds copies only: everything in it can be rebuilt from the database. */
public interface SearchEngine {
    /** Wrap matches in snippets. Private-use characters, so they cannot clash with text or need HTML escaping. */
    String MATCH_START = "";
    String MATCH_END = "";

    String FACET_TYPE = "type";
    String FACET_SITE = "site";
    String FACET_AUTHOR = "author";
    String FACET_MODIFIED = "modified";

    record Hit(UUID id, String snippet) {}

    record Facet(String value, long count) {}

    record Results(long total, List<Hit> hits, Map<String, List<Facet>> facets) {}

    /** The engine cannot be reached or refused a request. */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) { super(message, cause); }
        public UnavailableException(String message) { super(message); }
    }

    String name();

    /** Creates the index if needed. True when it was missing, so everything must be indexed again. */
    boolean ensureIndex();

    void index(List<SearchDocument> documents);

    void delete(Collection<UUID> ids);

    /** Removes every document of a site that no longer exists. */
    void deleteSite(UUID siteId);

    /**
     * Runs a query.
     *
     * @param aclSources nodes the caller can read through; only documents governed by one of them match.
     *                   Null means no restriction, for farm administrators.
     */
    Results search(SearchQuery query, Set<UUID> aclSources);

    long count();

    /** Makes everything indexed so far searchable now. */
    void refresh();

    /** Empties the index. */
    void reset();

    /** A short status line; throws {@link UnavailableException} when the engine is down. */
    String status();
}
