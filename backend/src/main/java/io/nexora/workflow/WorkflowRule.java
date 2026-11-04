package io.nexora.workflow;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * "When this happens, if these conditions hold, do these things" for one library or list. The definition names
 * columns, people, folders and lists rather than ids, so it survives export to another farm.
 */
@Entity
@Table(name = "workflow_rule")
public class WorkflowRule {
    @Id
    private UUID id;
    private UUID containerId;
    private String name;
    private boolean enabled;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> definition;
    private UUID createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    protected WorkflowRule() {}

    public WorkflowRule(UUID containerId, String name, boolean enabled, Map<String, Object> definition, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.containerId = containerId;
        this.name = name;
        this.enabled = enabled;
        this.definition = definition;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void update(String name, boolean enabled, Map<String, Object> definition) {
        this.name = name;
        this.enabled = enabled;
        this.definition = definition;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getContainerId() { return containerId; }
    public String getName() { return name; }
    public boolean isEnabled() { return enabled; }
    public Map<String, Object> getDefinition() { return definition; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
