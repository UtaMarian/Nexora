package io.nexora.sharing;

import io.nexora.acl.PermissionService;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.content.FileVersion;
import io.nexora.content.FileVersionRepository;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.storage.BlobStore;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a share link opens. Every failure looks the same from outside, so a link reveals nothing about what it pointed
 * at. Only the published version of a file is reachable, never a checked-out draft. Inside a shared folder, anything
 * with its own permissions stays hidden: whoever shared the folder may not have meant to share that too.
 */
@Service
public class LinkAccessService {
    private static final int MAX_PASSWORD_TRIES = 8;
    private static final Duration LOCK = Duration.ofMinutes(15);

    public record Entry(UUID id, String name, String kind, Long sizeBytes, Instant modifiedAt) {}

    /** @param entries what the folder holds; null for a file */
    public record Opened(UUID id, String name, String kind, Long sizeBytes, Instant modifiedAt, String sharedBy, Instant expiresAt,
                         List<Entry> entries) {}

    public record Content(String name, String mimeType, long sizeBytes, InputStream stream) {}

    private final ShareLinkRepository links;
    private final SharingService sharing;
    private final NodeRepository nodes;
    private final FileVersionRepository versions;
    private final PermissionService permissions;
    private final PrincipalRepository principals;
    private final PasswordEncoder encoder;
    private final BlobStore blobs;
    private final AuditService audit;

    public LinkAccessService(ShareLinkRepository links, SharingService sharing, NodeRepository nodes, FileVersionRepository versions,
                             PermissionService permissions, PrincipalRepository principals, PasswordEncoder encoder,
                             BlobStore blobs, AuditService audit) {
        this.links = links;
        this.sharing = sharing;
        this.nodes = nodes;
        this.versions = versions;
        this.permissions = permissions;
        this.principals = principals;
        this.encoder = encoder;
        this.blobs = blobs;
        this.audit = audit;
    }

    /** @param folderId a folder inside the shared one, or null for the shared item itself */
    @Transactional(noRollbackFor = ApiException.class)
    public Opened open(String token, String password, UUID folderId, boolean signedIn) {
        ShareLink link = usable(token, password, signedIn);
        Node root = target(link);
        Node node = folderId == null ? root : inside(root, folderId);
        link.used();
        String sharedBy = principals.findById(link.getCreatedBy()).map(Principal::getDisplayName).orElse(null);
        List<Entry> entries = null;
        if (node.getKind() != Node.Kind.FILE) {
            UUID source = permissions.aclSource(root).getId();
            entries = nodes.findLiveChildren(node.getId()).stream()
                    .filter(c -> c.getKind() == Node.Kind.FILE || c.getKind() == Node.Kind.FOLDER)
                    .filter(c -> permissions.aclSource(c).getId().equals(source))
                    .map(c -> new Entry(c.getId(), c.getName(), c.getKind().name(), c.getSizeBytes(), c.getModifiedAt())).toList();
        }
        return new Opened(node.getId(), node.getName(), node.getKind().name(), node.getSizeBytes(), node.getModifiedAt(), sharedBy,
                link.getExpiresAt(), entries);
    }

    /** @param fileId a file inside the shared folder, or null for the shared file itself */
    @Transactional(noRollbackFor = ApiException.class)
    public Content download(String token, String password, UUID fileId, CurrentUser user, boolean signedIn) {
        ShareLink link = usable(token, password, signedIn);
        Node root = target(link);
        Node file = fileId == null ? root : inside(root, fileId);
        if (file.getKind() != Node.Kind.FILE || file.getCurrentVersionId() == null) throw gone();
        FileVersion version = versions.findById(file.getCurrentVersionId()).orElseThrow(LinkAccessService::gone);
        audit.record(user, "SHARE_LINK_DOWNLOAD", file, Map.of("link", link.getId().toString(), "version", version.label()));
        try {
            return new Content(file.getName(), version.getMimeType(), version.getSizeBytes(), blobs.open(version.getStorageKey()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ShareLink usable(String token, String password, boolean signedIn) {
        ShareLink link = links.findByTokenHash(SharingService.hash(token)).orElseThrow(LinkAccessService::gone);
        Instant now = Instant.now();
        if (!link.isActive(now)) throw gone();
        if (link.getScope() == ShareLink.Scope.ANYONE && !sharing.anyoneLinksAllowed()) throw gone();
        if (link.getScope() == ShareLink.Scope.ORGANIZATION && !signedIn) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "sign_in_required", "Sign in to open this link");
        }
        if (link.getPasswordHash() != null) {
            if (link.getLockedUntil() != null && link.getLockedUntil().isAfter(now)) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "link_locked", "Too many wrong passwords. Try again later");
            }
            if (password == null || password.isEmpty()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "password_required", "This link needs a password");
            }
            if (!encoder.matches(password, link.getPasswordHash())) {
                link.wrongPassword(MAX_PASSWORD_TRIES, LOCK);
                throw new ApiException(HttpStatus.FORBIDDEN, "password_wrong", "That password is not right");
            }
            link.rightPassword();
        }
        return link;
    }

    private Node target(ShareLink link) {
        return nodes.findLive(link.getNodeId()).orElseThrow(LinkAccessService::gone);
    }

    private Node inside(Node root, UUID id) {
        Node node = nodes.findLive(id).orElseThrow(LinkAccessService::gone);
        boolean below = root.getKind() != Node.Kind.FILE && node.getPath().startsWith(root.getPath() + "/");
        if (!below && !node.getId().equals(root.getId())) throw gone();
        if (!permissions.aclSource(node).getId().equals(permissions.aclSource(root).getId())) throw gone();
        return node;
    }

    private static ApiException gone() {
        return new ApiException(HttpStatus.NOT_FOUND, "link_unavailable", "This link has expired or is no longer available");
    }
}
