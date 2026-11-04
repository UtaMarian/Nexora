package io.nexora.migration;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** A SharePoint farm (web application) to migrate from, with the account used to read it. */
@Entity
@Table(name = "migration_source")
public class MigrationSource {
    @Id
    private UUID id;
    private String name;
    private String baseUrl;
    @Enumerated(EnumType.STRING)
    private SharePointClient.Auth auth;
    private String username;
    private String domain;
    private String secret;
    private UUID createdBy;
    private Instant createdAt;

    protected MigrationSource() {}

    public MigrationSource(String name, String baseUrl, SharePointClient.Auth auth, String username, String domain,
                           String secret, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        update(name, baseUrl, auth, username, domain);
        this.secret = secret;
    }

    public void update(String name, String baseUrl, SharePointClient.Auth auth, String username, String domain) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.auth = auth;
        this.username = username;
        this.domain = domain;
    }

    public void setSecret(String secret) { this.secret = secret; }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getBaseUrl() { return baseUrl; }
    public SharePointClient.Auth getAuth() { return auth; }
    public String getUsername() { return username; }
    public String getDomain() { return domain; }
    public String getSecret() { return secret; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
