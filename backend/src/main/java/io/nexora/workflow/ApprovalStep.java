package io.nexora.workflow;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** One person's or group's part in an approval. Names are kept, so history survives if the account goes. */
@Entity
@Table(name = "approval_step")
public class ApprovalStep {
    public enum Decision { PENDING, APPROVED, REJECTED, SKIPPED }

    @Id
    private UUID id;
    private UUID approvalId;
    private int position;
    private UUID principalId;
    private String principalName;
    @Enumerated(EnumType.STRING)
    private Decision decision;
    private UUID decidedBy;
    private String decidedByName;
    private Instant decidedAt;
    private String comment;

    protected ApprovalStep() {}

    public ApprovalStep(UUID approvalId, int position, UUID principalId, String principalName) {
        this.id = UUID.randomUUID();
        this.approvalId = approvalId;
        this.position = position;
        this.principalId = principalId;
        this.principalName = principalName;
        this.decision = Decision.PENDING;
    }

    public static ApprovalStep restored(UUID approvalId, int position, UUID principalId, String principalName, Decision decision,
                                        UUID decidedBy, String decidedByName, Instant decidedAt, String comment) {
        ApprovalStep s = new ApprovalStep(approvalId, position, principalId, principalName);
        s.decision = decision;
        s.decidedBy = decidedBy;
        s.decidedByName = decidedByName;
        s.decidedAt = decidedAt;
        s.comment = comment;
        return s;
    }

    public void decide(Decision decision, UUID by, String byName, String comment) {
        this.decision = decision;
        this.decidedBy = by;
        this.decidedByName = byName;
        this.decidedAt = Instant.now();
        this.comment = comment;
    }

    public void skip() {
        this.decision = Decision.SKIPPED;
    }

    public void reassign(UUID principalId, String principalName) {
        this.principalId = principalId;
        this.principalName = principalName;
    }

    public boolean isPending() { return decision == Decision.PENDING; }
    public UUID getId() { return id; }
    public UUID getApprovalId() { return approvalId; }
    public int getPosition() { return position; }
    public UUID getPrincipalId() { return principalId; }
    public String getPrincipalName() { return principalName; }
    public Decision getDecision() { return decision; }
    public UUID getDecidedBy() { return decidedBy; }
    public String getDecidedByName() { return decidedByName; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getComment() { return comment; }
}
