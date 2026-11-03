package io.nexora.content;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A short-term exclusive edit lock, as taken by desktop Office over WebDAV while a document is open.
 * Unlike a check-out it expires on its own, and it does not hide changes from other users.
 */
@Entity
@Table(name = "edit_lock")
public class EditLock {
    @Id
    private UUID id;
    private UUID nodeId;
    private String token;
    private UUID ownerId;
    private String ownerInfo;
    private String client;
    private Instant createdAt;
    private Instant expiresAt;

    protected EditLock() {}

    public EditLock(UUID nodeId, UUID ownerId, String ownerInfo, String client, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.nodeId = nodeId;
        this.token = "opaquelocktoken:" + UUID.randomUUID();
        this.ownerId = ownerId;
        this.ownerInfo = ownerInfo;
        this.client = client;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() { return expiresAt.isBefore(Instant.now()); }

    public UUID getId() { return id; }
    public UUID getNodeId() { return nodeId; }
    public String getToken() { return token; }
    public UUID getOwnerId() { return ownerId; }
    public String getOwnerInfo() { return ownerInfo; }
    public String getClient() { return client; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
