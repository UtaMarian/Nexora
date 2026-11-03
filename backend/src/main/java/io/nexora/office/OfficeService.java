package io.nexora.office;

import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.content.*;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Glue between NEXORA's document model and a WOPI editor. Co-authoring is handled by the editor: everyone who
 * opens the same file gets the same WOPISrc, so they join one live editing session and see each other's cursors.
 */
@Service
public class OfficeService {
    private final OfficeProperties props;
    private final WopiDiscovery discovery;
    private final WopiTokenService tokens;
    private final DocumentService documents;
    private final ContentService content;
    private final PermissionService permissions;
    private final FileVersionRepository versions;
    private final PrincipalRepository principals;
    private final AuditService audit;

    public OfficeService(OfficeProperties props, WopiDiscovery discovery, WopiTokenService tokens,
                         DocumentService documents, ContentService content, PermissionService permissions,
                         FileVersionRepository versions, PrincipalRepository principals, AuditService audit) {
        this.props = props;
        this.discovery = discovery;
        this.tokens = tokens;
        this.documents = documents;
        this.content = content;
        this.permissions = permissions;
        this.versions = versions;
        this.principals = principals;
        this.audit = audit;
    }

    public record Session(String actionUrl, String accessToken, long accessTokenTtl, boolean canWrite, String fileName) {}

    /** Starts (or joins) a browser editing session for the caller. */
    @Transactional
    public Session open(CurrentUser user, UUID fileId, String browserOrigin, boolean preferView) {
        if (!props.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "office_disabled",
                "Browser editing is not enabled on this server");
        Node file = documents.requireFile(user, fileId);
        if (!discovery.available()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "office_unavailable",
                "The Office editor is not reachable right now");
        String ext = extension(file.getName());
        boolean canWrite = !preferView && canWrite(user, file);
        String base = discovery.urlFor(ext, canWrite ? "edit" : "view")
                .or(() -> discovery.urlFor(ext, "view"))
                .orElseThrow(() -> ApiException.badRequest("unsupported_format",
                        "'" + file.getName() + "' cannot be opened in the browser editor"));
        canWrite = canWrite && discovery.urlFor(ext, "edit").isPresent();
        String token = tokens.issue(user.id(), file.getId(), browserOrigin);
        WopiTokenService.Claims claims = tokens.verify(token, file.getId()).orElseThrow();
        String wopiSrc = props.wopiHostUrl() + "/wopi/files/" + file.getId();
        String url = base + "WOPISrc=" + URLEncoder.encode(wopiSrc, StandardCharsets.UTF_8) + "&closebutton=1";
        audit.record(user, "OFFICE_OPEN", file, Map.of("mode", canWrite ? "edit" : "view"));
        return new Session(url, token, claims.expiresAt().toEpochMilli(), canWrite, file.getName());
    }

    /** May this user save through the editor right now? */
    public boolean canWrite(CurrentUser user, Node file) {
        if (!permissions.has(user, file, Right.EDIT_ITEMS)) return false;
        if (file.isCheckedOut()) return file.getCheckedOutBy().equals(user.id());
        return !LibrarySettings.of(content.libraryOf(file)).requireCheckout();
    }

    /** Resolves the user behind a WOPI access token; the editor calls without the browser's session. */
    @Transactional(readOnly = true)
    public CurrentUser userFor(String token, UUID fileId) {
        WopiTokenService.Claims c = tokens.verify(token, fileId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid_token", "Invalid access token"));
        Principal p = principals.findById(c.userId()).filter(Principal::isEnabled)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid_token", "Invalid access token"));
        return new CurrentUser(p.getId(), p.getLogin(), p.getDisplayName(), p.isAdmin());
    }

    public String originFor(String token, UUID fileId) {
        return tokens.verify(token, fileId).map(WopiTokenService.Claims::origin).orElse(null);
    }

    /** WOPI CheckFileInfo for this user. */
    @Transactional(readOnly = true)
    public Map<String, Object> fileInfo(CurrentUser user, UUID fileId, String origin) {
        Node file = documents.requireFile(user, fileId);
        boolean draft = ownDraft(user, file);
        FileVersion current = versions.findById(file.getCurrentVersionId()).orElseThrow();
        boolean canWrite = canWrite(user, file);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("BaseFileName", file.getName());
        info.put("Size", draft ? file.getWorkingSizeBytes() : current.getSizeBytes());
        info.put("Version", draft ? "draft-" + file.getWorkingSha256() : current.getSha256());
        info.put("LastModifiedTime", timestamp(file));
        info.put("OwnerId", file.getCreatedBy().toString());
        info.put("UserId", user.id().toString());
        info.put("UserFriendlyName", user.displayName());
        info.put("UserCanWrite", canWrite);
        info.put("ReadOnly", !canWrite);
        info.put("UserCanNotWriteRelative", true);   // no "Save as" into arbitrary locations yet
        info.put("UserCanRename", false);
        info.put("SupportsUpdate", true);
        info.put("SupportsLocks", false);            // the editor coordinates co-authors itself
        info.put("SupportsRename", false);
        info.put("BreadcrumbDocName", file.getName());
        info.put("BreadcrumbFolderName", file.getPath().substring(0, file.getPath().lastIndexOf('/')));
        info.put("EnableInsertRemoteImage", false);
        info.put("DisableInactiveMessages", false);
        if (origin != null) info.put("PostMessageOrigin", origin);
        return info;
    }

    /**
     * Last-modified time of what the editor loaded. When the user is editing their own private draft there
     * is nobody to conflict with, so the time is of the published version and is not checked.
     */
    @Transactional(readOnly = true)
    public String timestamp(Node file) {
        Instant t = versions.findById(file.getCurrentVersionId()).map(FileVersion::getCreatedAt)
                .orElse(file.getModifiedAt());
        return t.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    public boolean ownDraft(CurrentUser user, Node file) {
        return file.isCheckedOut() && file.getCheckedOutBy().equals(user.id()) && file.getWorkingStorageKey() != null;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }
}
