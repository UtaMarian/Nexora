package io.nexora.search;

import jakarta.persistence.*;

import java.time.Instant;

/** The text of one blob, kept so that rebuilding the index never reads or OCRs a document again. */
@Entity
@Table(name = "search_text")
public class SearchText {

    public enum Status { OK, EMPTY, REJECTED, FAILED }

    @Id
    private String storageKey;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String body;
    private String detail;
    private Instant extractedAt;

    protected SearchText() {}

    public SearchText(String storageKey, Status status, String body, String detail) {
        this.storageKey = storageKey;
        this.status = status;
        this.body = body;
        this.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 500));
        this.extractedAt = Instant.now();
    }

    public String getStorageKey() { return storageKey; }
    public Status getStatus() { return status; }
    public String getBody() { return body; }
    public String getDetail() { return detail; }
    public Instant getExtractedAt() { return extractedAt; }
}
