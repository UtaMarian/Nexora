package io.nexora.content;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A storage limit on a top-level site. Its subsites count towards it. */
@Entity
@Table(name = "site_quota")
public class SiteQuota {
    @Id
    private UUID siteId;
    private long maxBytes;
    private Long warnBytes;
    private Instant warnedAt;
    private UUID updatedBy;
    private Instant updatedAt;

    protected SiteQuota() {}

    public SiteQuota(UUID siteId) {
        this.siteId = siteId;
    }

    public void set(long maxBytes, Long warnBytes, UUID actor) {
        this.maxBytes = maxBytes;
        this.warnBytes = warnBytes;
        this.warnedAt = null; // a new limit deserves a new warning
        this.updatedBy = actor;
        this.updatedAt = Instant.now();
    }

    public UUID getSiteId() { return siteId; }
    public long getMaxBytes() { return maxBytes; }
    public Long getWarnBytes() { return warnBytes; }
    public Instant getWarnedAt() { return warnedAt; }
    public void setWarnedAt(Instant warnedAt) { this.warnedAt = warnedAt; }
}
