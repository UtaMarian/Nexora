package io.nexora.alerts;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** A change waiting to be matched against alerts. */
@Entity
@Table(name = "alert_event")
public class AlertEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private UUID nodeId;
    private String action;
    private UUID actorId;
    private String actorName;
    private Instant occurredAt;

    protected AlertEvent() {}

    public AlertEvent(UUID nodeId, String action, UUID actorId, String actorName) {
        this.nodeId = nodeId;
        this.action = action;
        this.actorId = actorId;
        this.actorName = actorName;
        this.occurredAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getNodeId() { return nodeId; }
    public String getAction() { return action; }
    public UUID getActorId() { return actorId; }
    public String getActorName() { return actorName; }
    public Instant getOccurredAt() { return occurredAt; }
}
