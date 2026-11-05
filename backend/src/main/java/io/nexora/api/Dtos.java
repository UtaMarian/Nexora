package io.nexora.api;

import io.nexora.acl.PermissionLevel;
import io.nexora.acl.Right;
import io.nexora.content.ColumnDef;
import io.nexora.content.FileVersion;
import io.nexora.content.Node;
import io.nexora.identity.Principal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Request and response shapes of the public REST API (v1). */
public final class Dtos {
    private Dtos() {}

    // ---- principals ----

    public record PrincipalRef(UUID id, Principal.Kind kind, String login, String displayName) {
        public static PrincipalRef of(Principal p) {
            if (p == null) return null;
            return new PrincipalRef(p.getId(), p.getKind(), io.nexora.identity.CurrentUser.anonymousRequest() ? null : p.getLogin(), p.getDisplayName());
        }
    }

    public record Me(UUID id, String login, String displayName, String email, boolean admin, boolean searchEnabled, boolean anonymous) {}

    public record LoginRequest(@NotBlank String login, @NotBlank String password) {}

    public record CreateUserRequest(@NotBlank String login, String displayName, String email,
                                    @NotBlank String password, boolean admin) {}

    public record CreateGroupRequest(@NotBlank String name, String displayName) {}

    public record AddMemberRequest(@NotNull UUID principalId) {}

    // ---- nodes ----

    public record NodeDto(
            UUID id, Node.Kind kind, String name, String title, String description, String path,
            UUID parentId, UUID siteId,
            Long sizeBytes, String mimeType, String version,
            PrincipalRef checkedOutBy, Instant checkedOutAt, boolean hasDraft,
            PrincipalRef lockedBy, Instant lockExpiresAt,
            PrincipalRef createdBy, Instant createdAt, PrincipalRef modifiedBy, Instant modifiedAt,
            Map<String, Object> metadata, boolean inheritsPermissions,
            PermissionLevel myLevel, Set<Right> myRights, String approvalStatus) {}

    public record NodeDetail(NodeDto node, List<Crumb> breadcrumbs) {}

    public record Crumb(UUID id, Node.Kind kind, String name, String title, String path) {}

    public record CreateSiteRequest(@NotBlank String url, String title, String description,
                                    io.nexora.portal.SiteTemplate template) {}

    public record UpdateSiteRequest(String title, String description) {}

    public record CreateLibraryRequest(@NotBlank String name, String title, String description) {}

    public record CreateFolderRequest(@NotBlank String name) {}

    public record UpdateNodeRequest(String name, String title, String description, Map<String, Object> metadata) {}

    public record MoveRequest(@NotNull UUID targetParentId) {}

    // ---- files & versions ----

    public record CheckInRequest(String comment, boolean major) {}

    public record CommentRequest(String comment) {}

    public record VersionDto(UUID id, String label, boolean major, boolean current, long sizeBytes, String sha256,
                             String comment, PrincipalRef createdBy, Instant createdAt) {
        public static VersionDto of(FileVersion v, UUID currentId, Principal author) {
            return new VersionDto(v.getId(), v.label(), v.isMajor(), v.getId().equals(currentId), v.getSizeBytes(),
                    v.getSha256(), v.getComment(), PrincipalRef.of(author), v.getCreatedAt());
        }
    }

    // ---- library settings & columns ----

    public record LibrarySettingsDto(boolean versioningEnabled, boolean minorVersionsEnabled,
                                     boolean requireCheckout, Integer maxVersions) {}

    public record ColumnDto(UUID id, String internalName, String displayName, ColumnDef.Type type,
                            boolean required, List<String> choices, UUID lookupListId) {
        public static ColumnDto of(ColumnDef c) {
            return new ColumnDto(c.getId(), c.getInternalName(), c.getDisplayName(), c.getType(), c.isRequired(),
                    c.getChoices(), c.getLookupListId());
        }
    }

    public record CreateColumnRequest(@NotBlank String displayName, @NotNull ColumnDef.Type type, boolean required,
                                      List<String> choices, UUID lookupListId) {}

    public record NewDocumentRequest(io.nexora.content.DocumentService.NewDocument type, String name, UUID templateId) {}

    // ---- permissions ----

    public record AclEntryDto(PrincipalRef principal, PermissionLevel level) {}

    public record PermissionsDto(boolean inheritsPermissions, UUID sourceId, String sourcePath,
                                 List<AclEntryDto> entries) {}

    public record GrantRequest(@NotNull PermissionLevel level) {}

    public record BreakInheritanceRequest(Boolean copyParentPermissions) {}

    // ---- recycle bin & audit ----

    public record RecycleBinItem(UUID id, Node.Kind kind, String name, String path, UUID siteId,
                                 PrincipalRef deletedBy, Instant deletedAt) {}

    public record AuditDto(UUID id, Instant occurredAt, String actor, String action, UUID nodeId, String path,
                           Map<String, Object> details) {}

    public record PageDto<T>(List<T> items, int page, int size, long total) {}
}
