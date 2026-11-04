package io.nexora.search;

import java.time.Instant;
import java.util.UUID;

/**
 * What the search engine knows about one node or page.
 *
 * <p>{@code aclSource} is the node whose ACL decides who can see this document. Storing that rather than the list
 * of allowed people means a grant or a revoke takes effect on the next query, with nothing to reindex.
 */
public record SearchDocument(UUID id, String type, String title, String name, String nameText, String description,
                             String meta, String content, String path, String site, UUID siteId, String extension,
                             String author, Instant modifiedAt, long sizeBytes, UUID aclSource) {
}
