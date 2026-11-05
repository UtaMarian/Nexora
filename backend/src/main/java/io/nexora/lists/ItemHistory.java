package io.nexora.lists;

import io.nexora.content.Node;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Keeps a snapshot of a list item for every save. */
@Component
public class ItemHistory {
    private final ItemVersionRepository versions;

    public ItemHistory(ItemVersionRepository versions) {
        this.versions = versions;
    }

    /**
     * Call before changing an item. Items migrated or restored from an older package have no history yet, and without
     * this their state before the first edit would be lost.
     */
    public void baseline(Node item) {
        if (versions.latest(item.getId()) == 0) record(item, item.getModifiedBy(), item.getModifiedAt());
    }

    public void record(Node item, UUID by) {
        record(item, by, java.time.Instant.now());
    }

    private void record(Node item, UUID by, java.time.Instant at) {
        versions.save(new ItemVersion(item.getId(), versions.latest(item.getId()) + 1, item.getTitle(), item.getMetadata(), by, at));
    }
}
