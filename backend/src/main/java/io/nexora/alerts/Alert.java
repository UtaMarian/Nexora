package io.nexora.alerts;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** One person asking to hear about changes to a node and everything under it. */
@Entity
@Table(name = "alert")
public class Alert {
    public enum Frequency { IMMEDIATE, DAILY, WEEKLY }

    @Id
    private UUID id;
    private UUID userId;
    private UUID nodeId;
    @Enumerated(EnumType.STRING)
    private Frequency frequency;
    private Instant createdAt;
    private Instant lastSentAt;

    protected Alert() {}

    public Alert(UUID userId, UUID nodeId, Frequency frequency) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.nodeId = nodeId;
        this.frequency = frequency;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getNodeId() { return nodeId; }
    public Frequency getFrequency() { return frequency; }
    public void setFrequency(Frequency frequency) { this.frequency = frequency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSentAt() { return lastSentAt; }
    public void setLastSentAt(Instant lastSentAt) { this.lastSentAt = lastSentAt; }
}
