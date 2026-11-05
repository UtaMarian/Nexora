package io.nexora.lists;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A list item as it was after one save. */
@Entity
@Table(name = "list_item_version")
public class ItemVersion {
    @Id
    private UUID id;
    private UUID itemId;
    private int version;
    private String title;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata;
    private UUID createdBy;
    private Instant createdAt;

    protected ItemVersion() {}

    public ItemVersion(UUID itemId, int version, String title, Map<String, Object> metadata, UUID createdBy, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.itemId = itemId;
        this.version = version;
        this.title = title;
        this.metadata = new HashMap<>(metadata);
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getItemId() { return itemId; }
    public int getVersion() { return version; }
    public String getTitle() { return title; }
    public Map<String, Object> getMetadata() { return metadata == null ? Map.of() : metadata; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
