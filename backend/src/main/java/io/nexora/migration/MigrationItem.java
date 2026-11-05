package io.nexora.migration;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Where one SharePoint object landed in NEXORA: drives delta sync and old-URL redirects. */
@Entity
@Table(name = "migration_item")
public class MigrationItem {
    @Id
    private UUID id;
    private UUID taskId;
    private String kind;
    private String sourceKey;
    private String sourceUrl;
    private UUID nodeId;
    private Instant sourceModified;
    private String sourceVersion;

    protected MigrationItem() {}

    public MigrationItem(UUID taskId, String kind, String sourceKey, String sourceUrl, UUID nodeId) {
        this.id = UUID.randomUUID();
        this.taskId = taskId;
        this.kind = kind;
        this.sourceKey = sourceKey;
        this.sourceUrl = sourceUrl;
        this.nodeId = nodeId;
    }

    public void seen(String sourceUrl, UUID nodeId, Instant modified, String version) {
        this.sourceUrl = sourceUrl;
        this.nodeId = nodeId;
        this.sourceModified = modified;
        this.sourceVersion = version;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public String getKind() { return kind; }
    public String getSourceKey() { return sourceKey; }
    public String getSourceUrl() { return sourceUrl; }
    public UUID getNodeId() { return nodeId; }
    public Instant getSourceModified() { return sourceModified; }
    public String getSourceVersion() { return sourceVersion; }
}
