package io.nexora.sharing;

import io.nexora.acl.PermissionLevel;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Records who shared what with whom. The access itself is an ordinary ACL entry. */
@Entity
@Table(name = "share_grant")
public class ShareGrant {
    @Id
    private UUID id;
    private UUID nodeId;
    private UUID principalId;
    @Enumerated(EnumType.STRING)
    private PermissionLevel level;
    private UUID sharedBy;
    private Instant sharedAt;

    protected ShareGrant() {}

    public ShareGrant(UUID nodeId, UUID principalId, PermissionLevel level, UUID sharedBy) {
        this.id = UUID.randomUUID();
        this.nodeId = nodeId;
        this.principalId = principalId;
        this.level = level;
        this.sharedBy = sharedBy;
        this.sharedAt = Instant.now();
    }

    public void update(PermissionLevel level, UUID sharedBy) {
        this.level = level;
        this.sharedBy = sharedBy;
        this.sharedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getNodeId() { return nodeId; }
    public UUID getPrincipalId() { return principalId; }
    public PermissionLevel getLevel() { return level; }
    public UUID getSharedBy() { return sharedBy; }
    public Instant getSharedAt() { return sharedAt; }
}
