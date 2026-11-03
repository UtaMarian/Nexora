package io.nexora.portal;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "site_asset")
public class SiteAsset {
    @Id
    private UUID id;
    private UUID siteId;
    private String kind;
    private String mimeType;
    private String sha256;
    private byte[] data;
    private Instant updatedAt;

    protected SiteAsset() {}

    public SiteAsset(UUID siteId, String kind) {
        this.id = UUID.randomUUID();
        this.siteId = siteId;
        this.kind = kind;
    }

    public void replace(String mimeType, String sha256, byte[] data) {
        this.mimeType = mimeType;
        this.sha256 = sha256;
        this.data = data;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSiteId() { return siteId; }
    public String getKind() { return kind; }
    public String getMimeType() { return mimeType; }
    public String getSha256() { return sha256; }
    public byte[] getData() { return data; }
    public Instant getUpdatedAt() { return updatedAt; }
}
