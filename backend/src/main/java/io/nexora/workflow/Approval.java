package io.nexora.workflow;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** A request for people to approve or review one version of a file or list item. */
@Entity
@Table(name = "approval")
public class Approval {
    public enum Kind { APPROVAL, REVIEW }

    /** ANY: the first answer decides. ALL: everyone must approve. SEQUENTIAL: one after another, in order. */
    public enum Mode { ANY, ALL, SEQUENTIAL }

    public enum Status { PENDING, APPROVED, REJECTED, CHANGES_REQUESTED, CANCELLED }

    @Id
    private UUID id;
    private UUID nodeId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    @Enumerated(EnumType.STRING)
    private Mode mode;
    private String title;
    private String message;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String outcome;
    private UUID requestedBy;
    private String requestedByName;
    private String versionLabel;
    private Instant createdAt;
    private Instant dueAt;
    private Instant completedAt;
    private Instant remindedAt;
    /** Stops two people finishing the same approval at the same moment. */
    @Version
    private int lockVersion;

    protected Approval() {}

    public Approval(UUID nodeId, Kind kind, Mode mode, String title, String message, UUID requestedBy, String requestedByName,
                    String versionLabel, Instant dueAt) {
        this.id = UUID.randomUUID();
        this.nodeId = nodeId;
        this.kind = kind;
        this.mode = mode;
        this.title = title;
        this.message = message;
        this.status = Status.PENDING;
        this.requestedBy = requestedBy;
        this.requestedByName = requestedByName;
        this.versionLabel = versionLabel;
        this.createdAt = Instant.now();
        this.dueAt = dueAt;
    }

    /** A record brought back from a site package. */
    public static Approval restored(UUID nodeId, Kind kind, Mode mode, String title, String message, Status status, String outcome,
                                    UUID requestedBy, String requestedByName, String versionLabel, Instant createdAt, Instant dueAt,
                                    Instant completedAt) {
        Approval a = new Approval(nodeId, kind, mode, title, message, requestedBy, requestedByName, versionLabel, dueAt);
        a.status = status;
        a.outcome = outcome;
        a.createdAt = createdAt;
        a.completedAt = completedAt;
        return a;
    }

    public void finish(Status status, String outcome) {
        this.status = status;
        this.outcome = outcome;
        this.completedAt = Instant.now();
    }

    public boolean isPending() { return status == Status.PENDING; }
    public UUID getId() { return id; }
    public UUID getNodeId() { return nodeId; }
    public Kind getKind() { return kind; }
    public Mode getMode() { return mode; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public Status getStatus() { return status; }
    public String getOutcome() { return outcome; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getRequestedByName() { return requestedByName; }
    public String getVersionLabel() { return versionLabel; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDueAt() { return dueAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getRemindedAt() { return remindedAt; }
    public void setRemindedAt(Instant remindedAt) { this.remindedAt = remindedAt; }
}
