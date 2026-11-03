package io.nexora.content;

import io.nexora.storage.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Deletes blobs that no version or working copy references any more. Runs after the surrounding
 * transaction commits, so a rollback can never leave rows pointing at a deleted blob.
 */
@Component
public class BlobGarbageCollector {
    private static final Logger log = LoggerFactory.getLogger(BlobGarbageCollector.class);

    private final BlobStore store;
    private final FileVersionRepository versions;
    private final NodeRepository nodes;

    public BlobGarbageCollector(BlobStore store, FileVersionRepository versions, NodeRepository nodes) {
        this.store = store;
        this.versions = versions;
        this.nodes = nodes;
    }

    public void deleteIfOrphanedAfterCommit(Collection<String> keys) {
        Set<String> candidates = new HashSet<>(keys);
        candidates.remove(null);
        if (candidates.isEmpty()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sweep(candidates);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sweep(candidates);
            }
        });
    }

    private void sweep(Set<String> keys) {
        for (String key : keys) {
            try {
                if (versions.countByStorageKey(key) == 0 && nodes.countByWorkingStorageKey(key) == 0) {
                    store.delete(key);
                }
            } catch (Exception e) {
                log.warn("Could not delete orphaned blob {}; it will be retried by the next sweep", key, e);
            }
        }
    }
}
