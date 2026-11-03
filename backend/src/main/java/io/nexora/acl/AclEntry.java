package io.nexora.acl;

import jakarta.persistence.*;

import java.util.UUID;

/** Grants one principal one permission level on a node that has unique (non-inherited) permissions. */
@Entity
@Table(name = "acl_entry")
public class AclEntry {
    @Id
    private UUID id;
    private UUID nodeId;
    private UUID principalId;
    @Enumerated(EnumType.STRING)
    private PermissionLevel level;

    protected AclEntry() {}

    public AclEntry(UUID nodeId, UUID principalId, PermissionLevel level) {
        this.id = UUID.randomUUID();
        this.nodeId = nodeId;
        this.principalId = principalId;
        this.level = level;
    }

    public UUID getId() { return id; }
    public UUID getNodeId() { return nodeId; }
    public UUID getPrincipalId() { return principalId; }
    public PermissionLevel getLevel() { return level; }
    public void setLevel(PermissionLevel level) { this.level = level; }
}
