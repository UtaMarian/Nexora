package io.nexora.workflow;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Something that happened and that approvals or rules may need to react to. */
@Entity
@Table(name = "workflow_event")
public class WorkflowEvent {
    public enum Type {
        /** The content of an item under approval changed. Handled first, so a rule can then start a fresh approval. */
        STALE, CREATED, CHANGED, APPROVED, REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private UUID nodeId;
    @Enumerated(EnumType.STRING)
    private Type eventType;
    private UUID actorId;
    private String actorName;
    private Instant occurredAt;

    protected WorkflowEvent() {}

    public WorkflowEvent(UUID nodeId, Type eventType, UUID actorId, String actorName) {
        this.nodeId = nodeId;
        this.eventType = eventType;
        this.actorId = actorId;
        this.actorName = actorName;
        this.occurredAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getNodeId() { return nodeId; }
    public Type getEventType() { return eventType; }
    public UUID getActorId() { return actorId; }
    public String getActorName() { return actorName; }
}
