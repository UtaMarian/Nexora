package io.nexora.sharing;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** A read-only link to a file or folder. It always expires, and only a hash of its token is stored. */
@Entity
@Table(name = "share_link")
public class ShareLink {
    public enum Scope {
        /** Whoever holds the link, without signing in. */
        ANYONE,
        /** Anyone who can sign in to this farm. */
        ORGANIZATION
    }

    @Id
    private UUID id;
    private String tokenHash;
    private UUID nodeId;
    @Enumerated(EnumType.STRING)
    private Scope scope;
    private Instant expiresAt;
    private String passwordHash;
    private int failedAttempts;
    private Instant lockedUntil;
    private int useCount;
    private Instant lastUsedAt;
    private UUID createdBy;
    private Instant createdAt;
    private Instant revokedAt;

    protected ShareLink() {}

    public ShareLink(String tokenHash, UUID nodeId, Scope scope, Instant expiresAt, String passwordHash, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.tokenHash = tokenHash;
        this.nodeId = nodeId;
        this.scope = scope;
        this.expiresAt = expiresAt;
        this.passwordHash = passwordHash;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void used() {
        useCount++;
        lastUsedAt = Instant.now();
    }

    public void wrongPassword(int limit, java.time.Duration lock) {
        failedAttempts++;
        if (failedAttempts >= limit) {
            lockedUntil = Instant.now().plus(lock);
            failedAttempts = 0;
        }
    }

    public void rightPassword() {
        failedAttempts = 0;
        lockedUntil = null;
    }

    public void revoke() {
        revokedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getNodeId() { return nodeId; }
    public Scope getScope() { return scope; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getLockedUntil() { return lockedUntil; }
    public int getUseCount() { return useCount; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
