package io.nexora.alerts;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** One line waiting for a daily or weekly summary. */
@Entity
@Table(name = "alert_digest_item")
public class DigestItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private UUID alertId;
    private String summary;
    private String path;
    private Instant occurredAt;

    protected DigestItem() {}

    public DigestItem(UUID alertId, String summary, String path, Instant occurredAt) {
        this.alertId = alertId;
        this.summary = summary.length() > 1000 ? summary.substring(0, 1000) : summary;
        this.path = path;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public UUID getAlertId() { return alertId; }
    public String getSummary() { return summary; }
    public String getPath() { return path; }
    public Instant getOccurredAt() { return occurredAt; }
}
