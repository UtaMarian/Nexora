package io.nexora.search;

import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.search.SearchQueueEntry.Kind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Set;
import java.util.UUID;

/**
 * Tells the indexer what changed. Everything here joins the caller's transaction, so the index follows exactly what
 * was committed and a rolled-back change leaves no work behind.
 *
 * <p>Almost all changes arrive through {@link #audited}, because the audit log already sees every write. That includes
 * bulk updates of whole subtrees, which never pass through a JPA entity listener.
 */
@Service
public class SearchQueue {
    /** Changes to one node. */
    private static final Set<String> NODE_ACTIONS = Set.of(
            "SITE_UPDATE", "LIBRARY_CREATE", "LIST_CREATE", "ITEM_CREATE", "FOLDER_CREATE", "METADATA_UPDATE",
            "FILE_UPLOAD", "FILE_UPDATE", "CHECK_IN", "PUBLISH", "VERSION_RESTORE");
    /**
     * Changes that also reach everything below: paths, deletion, or which ACL governs the subtree. Grants and revokes
     * are absent on purpose: they take effect at query time.
     */
    private static final Set<String> SUBTREE_ACTIONS = Set.of(
            "SITE_CREATE", "SITE_TEMPLATE_APPLIED", "SITE_RESTORE", "SITE_DELETE", "RENAME", "MOVE",
            "DELETE", "RESTORE", "PURGE", "PERMISSIONS_BREAK_INHERITANCE", "PERMISSIONS_RESET_INHERITANCE");

    private final SearchQueueRepository queue;
    private final JdbcTemplate jdbc;

    public SearchQueue(SearchQueueRepository queue, JdbcTemplate jdbc) {
        this.queue = queue;
        this.jdbc = jdbc;
    }

    public void audited(String action, Node node) {
        if (node == null) return;
        if (NODE_ACTIONS.contains(action)) node(node.getId());
        else if (SUBTREE_ACTIONS.contains(action)) subtree(node.getId());
    }

    public void node(UUID id) {
        queue.save(new SearchQueueEntry(Kind.NODE, id));
    }

    public void subtree(UUID id) {
        queue.save(new SearchQueueEntry(Kind.SUBTREE, id));
    }

    public void page(UUID id) {
        queue.save(new SearchQueueEntry(Kind.PAGE, id));
    }

    public void text(UUID nodeId) {
        queue.save(new SearchQueueEntry(Kind.TEXT, nodeId));
    }

    /** Queues all nodes of a site, including deleted ones so they leave the index, and its published pages. */
    @Transactional
    public void wholeSite(UUID siteId) {
        insert("NODE", "node", "where site_id = ?", siteId);
        insert("PAGE", "site_page", "where site_id = ?", siteId);
    }

    /** Queues the items that went to the recycle bin together with {@code rootId}. */
    @Transactional
    public void deletedWith(UUID rootId) {
        insert("NODE", "node", "where deleted_root_id = ?", rootId);
    }

    /** Queues the live items under a path. */
    @Transactional
    public void liveBelow(String path) {
        insert("NODE", "node", "where path like ? escape '\\' and deleted_at is null", NodeRepository.likePrefix(path));
    }

    /** Queues all content, for a new or rebuilt index. */
    @Transactional
    public void everything() {
        insert("NODE", "node", "where deleted_at is null");
        insert("PAGE", "site_page", "where published_version > 0");
    }

    private void insert(String kind, String table, String where, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = Timestamp.from(SearchQueueEntry.dueNow());
        System.arraycopy(args, 0, all, 1, args.length);
        jdbc.update("insert into search_queue (kind, target_id, attempts, not_before) select '" + kind + "', id, 0, ? from "
                + table + " " + where, all);
    }
}
