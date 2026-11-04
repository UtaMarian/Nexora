package io.nexora.content;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** An immutable version of a file. Blobs are content-addressed, so versions can share a storage key. */
@Entity
@Table(name = "file_version")
public class FileVersion {
    @Id
    private UUID id;
    private UUID nodeId;
    private int major;
    private int minor;
    private String storageKey;
    private long sizeBytes;
    private String sha256;
    private String mimeType;
    private String fileName;
    private String comment;
    private UUID createdBy;
    private Instant createdAt;

    protected FileVersion() {}

    public FileVersion(UUID nodeId, int major, int minor, String storageKey, long sizeBytes, String sha256,
                       String mimeType, String fileName, String comment, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.nodeId = nodeId;
        this.major = major;
        this.minor = minor;
        this.storageKey = storageKey;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.mimeType = mimeType;
        this.fileName = fileName;
        this.comment = comment;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /** A version brought in by restore or migration, with its original author, date and label. */
    public static FileVersion imported(UUID nodeId, int major, int minor, String storageKey, long sizeBytes,
                                       String sha256, String mimeType, String fileName, String comment,
                                       UUID createdBy, Instant createdAt) {
        FileVersion v = new FileVersion(nodeId, major, minor, storageKey, sizeBytes, sha256, mimeType, fileName,
                comment, createdBy);
        if (createdAt != null) v.createdAt = createdAt;
        return v;
    }

    public String label() { return major + "." + minor; }
    public boolean isMajor() { return minor == 0; }

    public UUID getId() { return id; }
    public UUID getNodeId() { return nodeId; }
    public int getMajor() { return major; }
    public int getMinor() { return minor; }
    public String getStorageKey() { return storageKey; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getMimeType() { return mimeType; }
    public String getFileName() { return fileName; }
    public String getComment() { return comment; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
