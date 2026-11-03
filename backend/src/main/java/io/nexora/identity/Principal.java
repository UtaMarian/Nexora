package io.nexora.identity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A user or a group. Groups are either global (directory-style) or scoped to a site
 * (the SharePoint-style Owners / Members / Visitors groups created with each site).
 */
@Entity
@Table(name = "principal")
public class Principal {

    public enum Kind { USER, GROUP }

    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private String login;
    private String displayName;
    private String email;
    private String passwordHash;
    @Column(name = "is_admin")
    private boolean admin;
    @Column(name = "is_enabled")
    private boolean enabled = true;
    private String externalId;
    private UUID siteId;
    private Instant createdAt;

    protected Principal() {}

    public static Principal user(String login, String displayName, String passwordHash) {
        Principal p = new Principal();
        p.id = UUID.randomUUID();
        p.kind = Kind.USER;
        p.login = login;
        p.displayName = displayName;
        p.passwordHash = passwordHash;
        p.createdAt = Instant.now();
        return p;
    }

    public static Principal group(String name, String displayName, UUID siteId) {
        Principal p = new Principal();
        p.id = UUID.randomUUID();
        p.kind = Kind.GROUP;
        p.login = name;
        p.displayName = displayName;
        p.siteId = siteId;
        p.createdAt = Instant.now();
        return p;
    }

    public UUID getId() { return id; }
    public Kind getKind() { return kind; }
    public boolean isGroup() { return kind == Kind.GROUP; }
    public String getLogin() { return login; }
    public void setLogin(String login) { this.login = login; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isAdmin() { return admin; }
    public void setAdmin(boolean admin) { this.admin = admin; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public UUID getSiteId() { return siteId; }
    public Instant getCreatedAt() { return createdAt; }
}
