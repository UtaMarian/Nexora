package io.nexora.pages;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A snapshot of a page taken each time it is published. */
@Entity
@Table(name = "site_page_version")
public class SitePageVersion {
    @Id
    private UUID id;
    private UUID pageId;
    private int version;
    private String title;
    private String description;
    private UUID bannerImageId;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> canvas;
    private UUID publishedBy;
    private Instant publishedAt;

    protected SitePageVersion() {}

    public static SitePageVersion of(SitePage p) {
        SitePageVersion v = new SitePageVersion();
        v.id = UUID.randomUUID();
        v.pageId = p.getId();
        v.version = p.getPublishedVersion();
        v.title = p.getTitle();
        v.description = p.getDescription();
        v.bannerImageId = p.getBannerImageId();
        v.canvas = p.getCanvas();
        v.publishedBy = p.getPublishedBy();
        v.publishedAt = p.getPublishedAt();
        return v;
    }

    public UUID getId() { return id; }
    public UUID getPageId() { return pageId; }
    public int getVersion() { return version; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public UUID getBannerImageId() { return bannerImageId; }
    public Map<String, Object> getCanvas() { return canvas; }
    public UUID getPublishedBy() { return publishedBy; }
    public Instant getPublishedAt() { return publishedAt; }
}
