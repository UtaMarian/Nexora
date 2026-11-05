package io.nexora.content;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One object in the content tree: SITE → LIBRARY → FOLDER* → FILE.
 * Every node has a stable {@link #id} and a SharePoint-style server-relative {@link #path}
 * (e.g. {@code /sites/hr/Shared Documents/Policies/Leave.docx}) so migrated links resolve.
 */
@Entity
@Table(name = "node")
public class Node {

    public enum Kind {
        SITE, LIBRARY, FOLDER, FILE, LIST, LIST_ITEM;

        public boolean isContainer() { return this != FILE && this != LIST_ITEM; }

        /** Libraries and lists own a column schema. */
        public boolean hasColumns() { return this == LIBRARY || this == LIST; }
    }

    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private UUID parentId;
    private UUID siteId;
    private String name;
    private String path;
    private String title;
    private String description;
    private boolean inheritsPermissions = true;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata = new HashMap<>();
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> settings = new HashMap<>();

    private UUID currentVersionId;
    private Long sizeBytes;
    private String mimeType;

    private UUID checkedOutBy;
    private Instant checkedOutAt;
    private String workingStorageKey;
    private Long workingSizeBytes;
    private String workingSha256;
    private String workingMimeType;

    private UUID createdBy;
    private Instant createdAt;
    private UUID modifiedBy;
    private Instant modifiedAt;

    private Instant deletedAt;
    private UUID deletedBy;
    private UUID deletedRootId;

    protected Node() {}

    public static Node create(Kind kind, Node parent, String name, UUID actor) {
        Node n = new Node();
        n.id = UUID.randomUUID();
        n.kind = kind;
        n.name = name;
        if (parent == null) {
            n.siteId = n.id;
            n.path = "/sites/" + name;
            n.inheritsPermissions = false; // sites are always permission roots
        } else {
            n.parentId = parent.getId();
            n.path = parent.getPath() + "/" + name;
            // a subsite is a site of its own, with its own content, pages and groups
            n.siteId = kind == Kind.SITE ? n.id : parent.getSiteId();
            if (kind == Kind.SITE) n.inheritsPermissions = false;
        }
        Instant now = Instant.now();
        n.createdBy = actor;
        n.createdAt = now;
        n.modifiedBy = actor;
        n.modifiedAt = now;
        return n;
    }

    public void touch(UUID actor) {
        this.modifiedBy = actor;
        this.modifiedAt = Instant.now();
    }

    /** Keeps the original authors and dates of imported or migrated content. */
    public void importAudit(UUID createdBy, Instant createdAt, UUID modifiedBy, Instant modifiedAt) {
        if (createdBy != null) this.createdBy = createdBy;
        if (createdAt != null) this.createdAt = createdAt;
        if (modifiedBy != null) this.modifiedBy = modifiedBy;
        if (modifiedAt != null) this.modifiedAt = modifiedAt;
    }

    /** What a site's groups are called after: hr for /sites/hr, hr.payroll for /sites/hr/payroll. */
    public String groupPrefix() {
        return path.substring("/sites/".length()).replace('/', '.');
    }

    public boolean isDeleted() { return deletedAt != null; }
    public boolean isCheckedOut() { return checkedOutBy != null; }

    public void clearCheckout() {
        checkedOutBy = null;
        checkedOutAt = null;
        workingStorageKey = null;
        workingSizeBytes = null;
        workingSha256 = null;
        workingMimeType = null;
    }

    public UUID getId() { return id; }
    public Kind getKind() { return kind; }
    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isInheritsPermissions() { return inheritsPermissions; }
    public void setInheritsPermissions(boolean inheritsPermissions) { this.inheritsPermissions = inheritsPermissions; }
    public Map<String, Object> getMetadata() { return metadata == null ? (metadata = new HashMap<>()) : metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public Map<String, Object> getSettings() { return settings == null ? (settings = new HashMap<>()) : settings; }
    public void setSettings(Map<String, Object> settings) { this.settings = settings; }
    public UUID getCurrentVersionId() { return currentVersionId; }
    public void setCurrentVersionId(UUID currentVersionId) { this.currentVersionId = currentVersionId; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public UUID getCheckedOutBy() { return checkedOutBy; }
    public void setCheckedOutBy(UUID checkedOutBy) { this.checkedOutBy = checkedOutBy; }
    public Instant getCheckedOutAt() { return checkedOutAt; }
    public void setCheckedOutAt(Instant checkedOutAt) { this.checkedOutAt = checkedOutAt; }
    public String getWorkingStorageKey() { return workingStorageKey; }
    public void setWorkingStorageKey(String workingStorageKey) { this.workingStorageKey = workingStorageKey; }
    public Long getWorkingSizeBytes() { return workingSizeBytes; }
    public void setWorkingSizeBytes(Long workingSizeBytes) { this.workingSizeBytes = workingSizeBytes; }
    public String getWorkingSha256() { return workingSha256; }
    public void setWorkingSha256(String workingSha256) { this.workingSha256 = workingSha256; }
    public String getWorkingMimeType() { return workingMimeType; }
    public void setWorkingMimeType(String workingMimeType) { this.workingMimeType = workingMimeType; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getModifiedBy() { return modifiedBy; }
    public Instant getModifiedAt() { return modifiedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public UUID getDeletedBy() { return deletedBy; }
    public void setDeletedBy(UUID deletedBy) { this.deletedBy = deletedBy; }
    public UUID getDeletedRootId() { return deletedRootId; }
    public void setDeletedRootId(UUID deletedRootId) { this.deletedRootId = deletedRootId; }
}
