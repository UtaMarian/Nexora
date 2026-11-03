package io.nexora.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "audit_event")
public class AuditEvent {
    @Id
    private UUID id;
    private Instant occurredAt;
    private UUID actorId;
    private String actorLogin;
    private String action;
    private UUID nodeId;
    private UUID siteId;
    private String nodePath;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> details;

    protected AuditEvent() {}

    public AuditEvent(UUID actorId, String actorLogin, String action, UUID nodeId, UUID siteId,
                      String nodePath, Map<String, Object> details) {
        this.id = UUID.randomUUID();
        this.occurredAt = Instant.now();
        this.actorId = actorId;
        this.actorLogin = actorLogin;
        this.action = action;
        this.nodeId = nodeId;
        this.siteId = siteId;
        this.nodePath = nodePath;
        this.details = details;
    }

    public UUID getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getActorId() { return actorId; }
    public String getActorLogin() { return actorLogin; }
    public String getAction() { return action; }
    public UUID getNodeId() { return nodeId; }
    public UUID getSiteId() { return siteId; }
    public String getNodePath() { return nodePath; }
    public Map<String, Object> getDetails() { return details; }
}
