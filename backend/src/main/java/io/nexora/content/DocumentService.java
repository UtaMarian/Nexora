package io.nexora.content;

import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.identity.CurrentUser;
import io.nexora.storage.BlobStore;
import io.nexora.storage.StoredBlob;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Files and their versions.
 *
 * <p>Versioning follows SharePoint document libraries:
 * <ul>
 *   <li>Major-only libraries number versions 1.0, 2.0, 3.0 ...</li>
 *   <li>With minor versions, saves create drafts 0.1, 0.2 ...; publishing turns the latest draft into the next major.</li>
 *   <li>While a file is checked out, saves go to a private working copy that only the person who checked it out
 *       sees; check-in turns it into a new version, discard throws it away.</li>
 * </ul>
 */
@Service
@Transactional
public class DocumentService {
    private final NodeRepository nodes;
    private final FileVersionRepository versions;
    private final ContentService content;
    private final PermissionService permissions;
    private final BlobStore store;
    private final BlobGarbageCollector gc;
    private final AuditService audit;
    private final EditLockService editLocks;

    public DocumentService(NodeRepository nodes, FileVersionRepository versions, ContentService content,
                           PermissionService permissions, BlobStore store, BlobGarbageCollector gc, AuditService audit,
                           EditLockService editLocks) {
        this.editLocks = editLocks;
        this.nodes = nodes;
        this.versions = versions;
        this.content = content;
        this.permissions = permissions;
        this.store = store;
        this.gc = gc;
        this.audit = audit;
    }

    /**
     * Uploads a file into a library or folder. If a file with that name exists and {@code overwrite} is true,
     * the upload becomes a new version of it.
     */
    public Node upload(CurrentUser user, UUID parentId, String fileName, String declaredMime, Path data,
                       boolean overwrite, String comment) {
        Node parent = content.get(user, parentId);
        if (parent.getKind() != Node.Kind.LIBRARY && parent.getKind() != Node.Kind.FOLDER) {
            throw ApiException.badRequest("invalid_parent", "Files can only be uploaded to a library or folder");
        }
        // Check rights before looking at names, so people without write access learn nothing about siblings.
        permissions.require(user, parent, Right.ADD_ITEMS);
        String name = Names.item(fileName);
        Optional<Node> existing = nodes.findLiveByPath(parent.getPath() + "/" + name);
        if (existing.isPresent()) {
            Node file = existing.get();
            if (!overwrite || file.getKind() != Node.Kind.FILE) {
                throw ApiException.conflict("name_conflict", "An item named '" + file.getName() + "' already exists here");
            }
            return saveContent(user, file, declaredMime, data, comment);
        }

        StoredBlob blob = put(data);
        Node file = Node.create(Node.Kind.FILE, parent, name, user.id());
        nodes.saveAndFlush(file);
        addVersion(user, file, blob, MimeTypes.resolve(name, declaredMime), comment, false);
        audit.record(user, "FILE_UPLOAD", file, Map.of("size", blob.sizeBytes(), "version", currentLabel(file)));
        return file;
    }

    /** Saves new content for an existing file (new version, or the working copy if checked out). */
    public Node saveContent(CurrentUser user, UUID fileId, String declaredMime, Path data, String comment) {
        return saveContent(user, requireFile(user, fileId), declaredMime, data, comment);
    }

    private Node saveContent(CurrentUser user, Node file, String declaredMime, Path data, String comment) {
        permissions.require(user, file, Right.EDIT_ITEMS);
        if (editLocks.lockedByOther(user, file)) {
            throw ApiException.locked("'" + file.getName() + "' is open for editing by someone else");
        }
        String mime = MimeTypes.resolve(file.getName(), declaredMime);
        if (file.isCheckedOut()) {
            if (!file.getCheckedOutBy().equals(user.id())) {
                throw ApiException.locked("'" + file.getName() + "' is checked out by someone else");
            }
            StoredBlob blob = put(data);
            String previous = file.getWorkingStorageKey();
            file.setWorkingStorageKey(blob.key());
            file.setWorkingSha256(blob.sha256());
            file.setWorkingSizeBytes(blob.sizeBytes());
            file.setWorkingMimeType(mime);
            nodes.saveAndFlush(file);
            if (previous != null && !previous.equals(blob.key())) gc.deleteIfOrphanedAfterCommit(List.of(previous));
            audit.record(user, "FILE_SAVE_DRAFT", file, Map.of("size", blob.sizeBytes()));
            return file;
        }
        if (settingsFor(file).requireCheckout()) {
            throw new ApiException(HttpStatus.CONFLICT, "checkout_required",
                    "This library requires files to be checked out before they are changed");
        }
        StoredBlob blob = put(data);
        addVersion(user, file, blob, mime, comment, false);
        audit.record(user, "FILE_UPDATE", file, Map.of("size", blob.sizeBytes(), "version", currentLabel(file)));
        return file;
    }

    // ---- check-out / check-in ------------------------------------------------------------------

    public Node checkOut(CurrentUser user, UUID fileId) {
        Node file = requireFile(user, fileId);
        permissions.require(user, file, Right.EDIT_ITEMS);
        if (file.isCheckedOut()) {
            if (file.getCheckedOutBy().equals(user.id())) return file;
            throw ApiException.locked("'" + file.getName() + "' is already checked out by someone else");
        }
        file.setCheckedOutBy(user.id());
        file.setCheckedOutAt(Instant.now());
        audit.record(user, "CHECK_OUT", file);
        return file;
    }

    /**
     * Checks a file back in. If the working copy changed, it becomes a new version; {@code publishMajor}
     * makes it a major version in libraries that use minor versions.
     */
    public Node checkIn(CurrentUser user, UUID fileId, String comment, boolean publishMajor) {
        Node file = requireCheckedOut(user, fileId);
        String workingKey = file.getWorkingStorageKey();
        if (workingKey != null) {
            StoredBlob blob = new StoredBlob(workingKey, file.getWorkingSha256(), file.getWorkingSizeBytes());
            String mime = file.getWorkingMimeType();
            file.clearCheckout();
            addVersion(user, file, blob, mime, comment, publishMajor);
        } else {
            file.clearCheckout();
            if (publishMajor && settingsFor(file).minorVersionsEnabled() && !current(file).isMajor()) {
                publishLatest(user, file, comment);
            }
        }
        nodes.saveAndFlush(file);
        audit.record(user, "CHECK_IN", file, Map.of("version", currentLabel(file)));
        return file;
    }

    public Node discardCheckOut(CurrentUser user, UUID fileId) {
        Node file = requireCheckedOut(user, fileId);
        String workingKey = file.getWorkingStorageKey();
        file.clearCheckout();
        nodes.saveAndFlush(file);
        if (workingKey != null) gc.deleteIfOrphanedAfterCommit(List.of(workingKey));
        audit.record(user, "DISCARD_CHECK_OUT", file);
        return file;
    }

    private Node requireCheckedOut(CurrentUser user, UUID fileId) {
        Node file = requireFile(user, fileId);
        if (!file.isCheckedOut()) throw ApiException.conflict("not_checked_out", "The file is not checked out");
        if (!file.getCheckedOutBy().equals(user.id())) {
            permissions.require(user, file, Right.OVERRIDE_CHECKOUT);
        } else {
            permissions.require(user, file, Right.EDIT_ITEMS);
        }
        return file;
    }

    // ---- versions --------------------------------------------------------------------------------

    /** Publishes the latest minor version as the next major version (0.3 → 1.0). */
    public Node publish(CurrentUser user, UUID fileId, String comment) {
        Node file = requireFile(user, fileId);
        permissions.require(user, file, Right.EDIT_ITEMS);
        content.assertNotLockedByOthers(user, file);
        if (file.isCheckedOut()) {
            throw ApiException.conflict("checked_out", "Check the file in before publishing it");
        }
        publishLatest(user, file, comment);
        audit.record(user, "PUBLISH", file, Map.of("version", currentLabel(file)));
        return file;
    }

    private void publishLatest(CurrentUser user, Node file, String comment) {
        if (!settingsFor(file).minorVersionsEnabled()) {
            throw ApiException.badRequest("minor_versions_disabled", "This library does not use minor versions");
        }
        FileVersion latest = current(file);
        if (latest.isMajor()) throw ApiException.conflict("already_published", "The latest version is already published");
        addVersion(user, file, new StoredBlob(latest.getStorageKey(), latest.getSha256(), latest.getSizeBytes()),
                latest.getMimeType(), comment, true);
    }

    @Transactional(readOnly = true)
    public List<FileVersion> history(CurrentUser user, UUID fileId) {
        Node file = requireFile(user, fileId);
        return versions.findHistory(file.getId());
    }

    public Node restoreVersion(CurrentUser user, UUID fileId, UUID versionId, String comment) {
        Node file = requireFile(user, fileId);
        permissions.require(user, file, Right.EDIT_ITEMS);
        if (file.isCheckedOut()) {
            throw ApiException.locked("Check the file in, or discard the checkout, before restoring a version");
        }
        FileVersion v = versionOf(file, versionId);
        addVersion(user, file, new StoredBlob(v.getStorageKey(), v.getSha256(), v.getSizeBytes()), v.getMimeType(),
                comment == null || comment.isBlank() ? "Restored from version " + v.label() : comment, false);
        audit.record(user, "VERSION_RESTORE", file, Map.of("from", v.label(), "version", currentLabel(file)));
        return file;
    }

    public void deleteVersion(CurrentUser user, UUID fileId, UUID versionId) {
        Node file = requireFile(user, fileId);
        permissions.require(user, file, Right.DELETE_ITEMS);
        FileVersion v = versionOf(file, versionId);
        if (v.getId().equals(file.getCurrentVersionId())) {
            throw ApiException.conflict("current_version", "The current version cannot be deleted");
        }
        versions.delete(v);
        versions.flush();
        gc.deleteIfOrphanedAfterCommit(List.of(v.getStorageKey()));
        audit.record(user, "VERSION_DELETE", file, Map.of("version", v.label()));
    }

    /** Creates the next version according to library settings and makes it current. */
    private void addVersion(CurrentUser user, Node file, StoredBlob blob, String mime, String comment,
                            boolean publishMajor) {
        LibrarySettings settings = settingsFor(file);
        List<FileVersion> history = versions.findHistory(file.getId());
        int major;
        int minor;
        Set<String> released = new HashSet<>();
        if (!settings.versioningEnabled()) {
            history.forEach(v -> released.add(v.getStorageKey()));
            versions.deleteAll(history);
            versions.flush();
            history = List.of();
            major = 1;
            minor = 0;
        } else if (isPlaceholder(history, user)) {
            // Explorer and many WebDAV clients create a file empty, then upload its content right after:
            // replace that placeholder instead of keeping an empty "1.0" in the history.
            FileVersion placeholder = history.get(0);
            released.add(placeholder.getStorageKey());
            versions.delete(placeholder);
            versions.flush();
            major = placeholder.getMajor();
            minor = placeholder.getMinor();
            history = List.of();
        } else if (history.isEmpty()) {
            major = settings.minorVersionsEnabled() && !publishMajor ? 0 : 1;
            minor = settings.minorVersionsEnabled() && !publishMajor ? 1 : 0;
        } else {
            FileVersion latest = history.get(0);
            if (settings.minorVersionsEnabled() && !publishMajor) {
                major = latest.getMajor();
                minor = latest.getMinor() + 1;
            } else {
                major = latest.getMajor() + 1;
                minor = 0;
            }
        }
        FileVersion v = versions.save(new FileVersion(file.getId(), major, minor, blob.key(), blob.sizeBytes(),
                blob.sha256(), mime, file.getName(), blankToNull(comment), user.id()));
        file.setCurrentVersionId(v.getId());
        file.setSizeBytes(blob.sizeBytes());
        file.setMimeType(mime);
        file.touch(user.id());
        nodes.save(file);

        Integer max = settings.maxVersions();
        if (settings.versioningEnabled() && max != null && history.size() + 1 > max) {
            List<FileVersion> excess = history.subList(max - 1, history.size()); // history is newest-first
            excess.forEach(old -> released.add(old.getStorageKey()));
            versions.deleteAll(new ArrayList<>(excess));
        }
        versions.flush();
        released.remove(blob.key());
        gc.deleteIfOrphanedAfterCommit(released);
    }

    // ---- download --------------------------------------------------------------------------------

    public record Download(String fileName, String mimeType, long sizeBytes, String sha256, String versionLabel,
                           InputStream stream) {}

    /**
     * Opens the file's content: the given version, or else the caller's working copy if they have the file
     * checked out, or else the current version.
     */
    public Download open(CurrentUser user, UUID fileId, UUID versionId) {
        Node file = requireFile(user, fileId);
        Download d;
        if (versionId != null) {
            FileVersion v = versionOf(file, versionId);
            d = download(v.getFileName(), v.getMimeType(), v.getSizeBytes(), v.getSha256(), v.label(), v.getStorageKey());
        } else if (file.isCheckedOut() && file.getCheckedOutBy().equals(user.id()) && file.getWorkingStorageKey() != null) {
            d = download(file.getName(), file.getWorkingMimeType(), file.getWorkingSizeBytes(), file.getWorkingSha256(),
                    "draft", file.getWorkingStorageKey());
        } else {
            FileVersion v = current(file);
            d = download(file.getName(), v.getMimeType(), v.getSizeBytes(), v.getSha256(), v.label(), v.getStorageKey());
        }
        audit.record(user, "FILE_DOWNLOAD", file, Map.of("version", d.versionLabel()));
        return d;
    }

    private Download download(String name, String mime, long size, String sha, String label, String key) {
        try {
            return new Download(name, mime, size, sha, label, store.open(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Blob " + key + " is unreadable", e);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Node requireFile(CurrentUser user, UUID fileId) {
        Node node = content.get(user, fileId);
        if (node.getKind() != Node.Kind.FILE) throw ApiException.badRequest("not_a_file", "Not a file");
        return node;
    }

    private FileVersion current(Node file) {
        return versions.findById(file.getCurrentVersionId())
                .orElseThrow(() -> new IllegalStateException("File " + file.getId() + " has no current version"));
    }

    private FileVersion versionOf(Node file, UUID versionId) {
        return versions.findById(versionId)
                .filter(v -> v.getNodeId().equals(file.getId()))
                .orElseThrow(() -> ApiException.notFound("Version"));
    }

    private String currentLabel(Node file) {
        return file.getCurrentVersionId() == null ? "" : versions.findById(file.getCurrentVersionId())
                .map(FileVersion::label).orElse("");
    }

    private LibrarySettings settingsFor(Node file) {
        return LibrarySettings.of(content.libraryOf(file));
    }

    private StoredBlob put(Path data) {
        try {
            return store.put(data);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store upload", e);
        }
    }

    /** A single empty version created by the same user in the last few minutes. */
    private static boolean isPlaceholder(List<FileVersion> history, CurrentUser user) {
        if (history.size() != 1) return false;
        FileVersion v = history.get(0);
        return v.getSizeBytes() == 0 && v.getCreatedBy().equals(user.id())
                && v.getCreatedAt().isAfter(Instant.now().minusSeconds(300));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
