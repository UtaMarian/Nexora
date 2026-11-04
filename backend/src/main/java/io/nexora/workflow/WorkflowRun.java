package io.nexora.workflow;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** One time a rule ran, so people can see what it did and why it failed. */
@Entity
@Table(name = "workflow_run")
public class WorkflowRun {
    public enum Status { DONE, SKIPPED, FAILED }

    @Id
    private UUID id;
    private UUID ruleId;
    private UUID nodeId;
    private String nodePath;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String detail;
    /** For date rules: stops one date firing twice. */
    private String fireKey;
    private Instant ranAt;

    protected WorkflowRun() {}

    public WorkflowRun(UUID ruleId, UUID nodeId, String nodePath, Status status, String detail, String fireKey) {
        this.id = UUID.randomUUID();
        this.ruleId = ruleId;
        this.nodeId = nodeId;
        this.nodePath = nodePath;
        this.status = status;
        this.detail = detail == null || detail.length() <= 1000 ? detail : detail.substring(0, 1000);
        this.fireKey = fireKey;
        this.ranAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getRuleId() { return ruleId; }
    public UUID getNodeId() { return nodeId; }
    public String getNodePath() { return nodePath; }
    public Status getStatus() { return status; }
    public String getDetail() { return detail; }
    public Instant getRanAt() { return ranAt; }
}
