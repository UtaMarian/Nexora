package io.nexora.migration;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Copies one SharePoint site (web) into one NEXORA site; re-running it copies only what changed. */
@Entity
@Table(name = "migration_task")
public class MigrationTask {
    public enum Status { NEW, SCANNED, COPIED, SYNCED, FAILED }

    @Id
    private UUID id;
    private UUID sourceId;
    private String sourceWeb;
    private String targetSite;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> options;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> scan;
    @Enumerated(EnumType.STRING)
    private Status status;
    private UUID lastJobId;
    private Instant lastSyncAt;
    private UUID createdBy;
    private Instant createdAt;

    protected MigrationTask() {}

    public MigrationTask(UUID sourceId, String sourceWeb, String targetSite, Map<String, Object> options, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.sourceId = sourceId;
        this.sourceWeb = sourceWeb;
        this.targetSite = targetSite;
        this.options = options;
        this.status = Status.NEW;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void setOptions(Map<String, Object> options) { this.options = options; }
    public void setScan(Map<String, Object> scan) { this.scan = scan; }
    public void setStatus(Status status) { this.status = status; }
    public void setLastJobId(UUID lastJobId) { this.lastJobId = lastJobId; }
    public void setLastSyncAt(Instant lastSyncAt) { this.lastSyncAt = lastSyncAt; }

    public UUID getId() { return id; }
    public UUID getSourceId() { return sourceId; }
    public String getSourceWeb() { return sourceWeb; }
    public String getTargetSite() { return targetSite; }
    public Map<String, Object> getOptions() { return options; }
    public Map<String, Object> getScan() { return scan; }
    public Status getStatus() { return status; }
    public UUID getLastJobId() { return lastJobId; }
    public Instant getLastSyncAt() { return lastSyncAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
