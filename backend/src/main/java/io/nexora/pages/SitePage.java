package io.nexora.pages;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A modern page or news post in a site. Readers see the published fields; editors work on the draft fields,
 * which are null when there are no unpublished changes.
 */
@Entity
@Table(name = "site_page")
public class SitePage {

    public enum Kind { PAGE, NEWS }

    @Id
    private UUID id;
    private UUID siteId;
    private String name;
    @Enumerated(EnumType.STRING)
    private Kind kind;

    private String title;
    private String description;
    private UUID bannerImageId;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> canvas;

    private String draftTitle;
    private String draftDescription;
    private UUID draftBannerId;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> draftCanvas;

    private int revision;
    private int publishedVersion;

    private UUID createdBy;
    private Instant createdAt;
    private UUID modifiedBy;
    private Instant modifiedAt;
    private UUID publishedBy;
    private Instant publishedAt;

    protected SitePage() {}

    public SitePage(UUID siteId, String name, Kind kind, String title, UUID actor) {
        this.id = UUID.randomUUID();
        this.siteId = siteId;
        this.name = name;
        this.kind = kind;
        this.title = title;
        this.draftTitle = title;
        this.createdBy = actor;
        this.createdAt = Instant.now();
        touch(actor);
    }

    /** Recreates a page from a backup or another farm (new id, same content and history counters). */
    public static SitePage restored(UUID siteId, String name, Kind kind, String title, String description,
                                    UUID banner, Map<String, Object> canvas, String draftTitle, String draftDescription,
                                    UUID draftBanner, Map<String, Object> draftCanvas, int publishedVersion,
                                    UUID createdBy, Instant createdAt, UUID modifiedBy, Instant modifiedAt,
                                    UUID publishedBy, Instant publishedAt) {
        SitePage p = new SitePage(siteId, name, kind, title, createdBy);
        p.description = description;
        p.bannerImageId = banner;
        p.canvas = canvas;
        p.draftTitle = draftTitle;
        p.draftDescription = draftDescription;
        p.draftBannerId = draftBanner;
        p.draftCanvas = draftCanvas;
        p.publishedVersion = publishedVersion;
        if (createdAt != null) p.createdAt = createdAt;
        p.modifiedBy = modifiedBy == null ? createdBy : modifiedBy;
        if (modifiedAt != null) p.modifiedAt = modifiedAt;
        p.publishedBy = publishedBy;
        p.publishedAt = publishedAt;
        return p;
    }

    public void touch(UUID actor) {
        modifiedBy = actor;
        modifiedAt = Instant.now();
        revision++;
    }

    public boolean isPublished() { return publishedVersion > 0; }
    public boolean hasDraft() { return draftCanvas != null || draftTitle != null; }

    public void saveDraft(String title, String description, UUID banner, Map<String, Object> canvas, UUID actor) {
        draftTitle = title;
        draftDescription = description;
        draftBannerId = banner;
        draftCanvas = canvas;
        touch(actor);
    }

    public void publish(UUID actor) {
        title = draftTitle;
        description = draftDescription;
        bannerImageId = draftBannerId;
        canvas = draftCanvas;
        clearDraft();
        publishedVersion++;
        publishedBy = actor;
        publishedAt = Instant.now();
        touch(actor);
    }

    public void clearDraft() {
        draftTitle = null;
        draftDescription = null;
        draftBannerId = null;
        draftCanvas = null;
    }

    public UUID getId() { return id; }
    public UUID getSiteId() { return siteId; }
    public String getName() { return name; }
    public Kind getKind() { return kind; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public UUID getBannerImageId() { return bannerImageId; }
    public Map<String, Object> getCanvas() { return canvas; }
    public String getDraftTitle() { return draftTitle; }
    public String getDraftDescription() { return draftDescription; }
    public UUID getDraftBannerId() { return draftBannerId; }
    public Map<String, Object> getDraftCanvas() { return draftCanvas; }
    public int getRevision() { return revision; }
    public int getPublishedVersion() { return publishedVersion; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getModifiedBy() { return modifiedBy; }
    public Instant getModifiedAt() { return modifiedAt; }
    public UUID getPublishedBy() { return publishedBy; }
    public Instant getPublishedAt() { return publishedAt; }
}
