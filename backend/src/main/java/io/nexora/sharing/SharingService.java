package io.nexora.sharing;

import io.nexora.acl.PermissionLevel;
import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.admin.FarmSetting;
import io.nexora.admin.FarmSettingRepository;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.content.ContentService;
import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.mail.MailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Sharing, for anyone who can edit an item. People get an ordinary ACL entry, so everything that respects permissions
 * respects a share, and a record so they can find it under "Shared with me". Links are read-only and always expire.
 * Nobody can give more than Contribute this way; higher levels stay with whoever manages permissions.
 */
@Service
@Transactional
public class SharingService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ANYONE_SETTING = "sharing.anyone-links";
    private static final Set<PermissionLevel> SHAREABLE = EnumSet.of(PermissionLevel.READ, PermissionLevel.CONTRIBUTE);
    private static final Set<Node.Kind> LINKABLE = EnumSet.of(Node.Kind.FILE, Node.Kind.FOLDER);

    /** @param token shown once, when the link is created */
    public record CreatedLink(ShareLink link, String token) {}

    private final ShareLinkRepository links;
    private final ShareGrantRepository grants;
    private final ContentService content;
    private final PermissionService permissions;
    private final PrincipalRepository principals;
    private final FarmSettingRepository settings;
    private final PasswordEncoder encoder;
    private final MailService mail;
    private final AuditService audit;
    private final int maxLinkDays;

    public SharingService(ShareLinkRepository links, ShareGrantRepository grants, ContentService content, PermissionService permissions,
                          PrincipalRepository principals, FarmSettingRepository settings, PasswordEncoder encoder,
                          MailService mail, AuditService audit, @Value("${nexora.sharing.max-link-days:365}") int maxLinkDays) {
        this.links = links;
        this.grants = grants;
        this.content = content;
        this.permissions = permissions;
        this.principals = principals;
        this.settings = settings;
        this.encoder = encoder;
        this.mail = mail;
        this.audit = audit;
        this.maxLinkDays = maxLinkDays;
    }

    // ---- farm policy -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public boolean anyoneLinksAllowed() {
        return settings.findById(ANYONE_SETTING).map(s -> Boolean.parseBoolean(s.getValue())).orElse(true);
    }

    public void setAnyoneLinksAllowed(CurrentUser actor, boolean allowed) {
        settings.save(new FarmSetting(ANYONE_SETTING, String.valueOf(allowed), actor.id()));
        audit.record(actor, allowed ? "SHARE_ANYONE_LINKS_ALLOW" : "SHARE_ANYONE_LINKS_BLOCK", null);
    }

    public int maxLinkDays() {
        return maxLinkDays;
    }

    // ---- links -----------------------------------------------------------------------------------

    public CreatedLink createLink(CurrentUser user, UUID nodeId, ShareLink.Scope scope, int days, String password) {
        Node node = editable(user, nodeId);
        if (!LINKABLE.contains(node.getKind())) throw ApiException.badRequest("not_linkable", "Only files and folders can have a link");
        ShareLink.Scope s = scope == null ? ShareLink.Scope.ORGANIZATION : scope;
        if (s == ShareLink.Scope.ANYONE && !anyoneLinksAllowed()) {
            throw ApiException.forbidden("An administrator has turned off links that work without signing in");
        }
        if (days < 1 || days > maxLinkDays) {
            throw ApiException.badRequest("invalid_expiry", "A link must expire within 1 to " + maxLinkDays + " days");
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String hash = password == null || password.isBlank() ? null : encoder.encode(password);
        ShareLink link = links.save(new ShareLink(hash(token), node.getId(), s, Instant.now().plus(Duration.ofDays(days)), hash, user.id()));
        audit.record(user, "SHARE_LINK_CREATE", node, Map.of("scope", s.name(), "days", days, "password", hash != null));
        return new CreatedLink(link, token);
    }

    public void revokeLink(CurrentUser user, UUID linkId) {
        ShareLink link = links.findById(linkId).orElseThrow(() -> ApiException.notFound("Link"));
        Node node = content.get(user, link.getNodeId());
        if (!link.getCreatedBy().equals(user.id())) permissions.require(user, node, Right.MANAGE_PERMISSIONS);
        link.revoke();
        audit.record(user, "SHARE_LINK_REVOKE", node);
    }

    /** Stops any active link, for administrators. */
    public void revokeAsAdmin(CurrentUser actor, UUID linkId) {
        ShareLink link = links.findById(linkId).orElseThrow(() -> ApiException.notFound("Link"));
        link.revoke();
        audit.record(actor, "SHARE_LINK_REVOKE", null, Map.of("link", linkId.toString()));
    }

    @Transactional(readOnly = true)
    public List<ShareLink> activeLinks(int limit) {
        return links.active(Instant.now(), PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public List<ShareLink> links(CurrentUser user, UUID nodeId) {
        Node node = content.get(user, nodeId);
        permissions.require(user, node, Right.EDIT_ITEMS);
        return links.findByNodeIdOrderByCreatedAtDesc(nodeId).stream().filter(l -> l.isActive(Instant.now())).toList();
    }

    // ---- people ----------------------------------------------------------------------------------

    public void share(CurrentUser user, UUID nodeId, List<UUID> principalIds, PermissionLevel level, String message) {
        Node node = editable(user, nodeId);
        if (node.getKind() == Node.Kind.SITE) throw ApiException.badRequest("not_shareable", "Add people to the site's groups instead");
        PermissionLevel chosen = level == null ? PermissionLevel.READ : level;
        if (!SHAREABLE.contains(chosen)) {
            throw ApiException.forbidden("Sharing can give Read or Contribute. Ask a site owner for more");
        }
        if (principalIds == null || principalIds.isEmpty()) throw ApiException.badRequest("nobody", "Choose someone to share with");
        // the first share gives the item its own permissions, starting from what it already had
        if (node.isInheritsPermissions()) {
            permissions.breakInheritance(node, true);
            audit.record(user, "PERMISSIONS_BREAK_INHERITANCE", node, Map.of("copied", true, "by", "sharing"));
        }
        String title = node.getTitle() != null ? node.getTitle() : node.getName();
        for (UUID id : principalIds) {
            Principal p = principals.findById(id).orElseThrow(() -> ApiException.notFound("Person or group"));
            PermissionLevel existing = permissions.entries(node).stream().filter(e -> e.getPrincipalId().equals(id))
                    .map(e -> e.getLevel()).findFirst().orElse(null);
            // sharing never takes away access someone already has
            PermissionLevel kept = PermissionLevel.max(existing, chosen);
            if (kept != existing) permissions.grant(node, id, kept);
            ShareGrant grant = grants.findByNodeIdAndPrincipalId(node.getId(), id).orElse(null);
            if (grant == null) grants.save(new ShareGrant(node.getId(), id, kept, user.id()));
            else grant.update(kept, user.id());
            audit.record(user, "SHARE_GRANT", node, Map.of("principal", p.getLogin(), "level", kept.name()));
            List<String> lines = new ArrayList<>(List.of(user.displayName() + " shared " + title + " with you"));
            if (message != null && !message.isBlank()) lines.add(message.strip());
            mail.queue(p.getEmail(), "[NEXORA] " + user.displayName() + " shared " + title + " with you", lines, "Open", mail.link(node.getPath()));
        }
    }

    public void unshare(CurrentUser user, UUID grantId) {
        ShareGrant grant = grants.findById(grantId).orElseThrow(() -> ApiException.notFound("Share"));
        Node node = content.get(user, grant.getNodeId());
        if (!grant.getSharedBy().equals(user.id())) permissions.require(user, node, Right.MANAGE_PERMISSIONS);
        permissions.revoke(node, grant.getPrincipalId());
        grants.delete(grant);
        audit.record(user, "SHARE_REVOKE", node, Map.of("principal", grant.getPrincipalId().toString()));
    }

    @Transactional(readOnly = true)
    public List<ShareGrant> grants(CurrentUser user, UUID nodeId) {
        Node node = content.get(user, nodeId);
        permissions.require(user, node, Right.EDIT_ITEMS);
        return grants.findByNodeIdOrderBySharedAtDesc(nodeId);
    }

    /** What was shared with this person or their groups and is still theirs to open. */
    @Transactional(readOnly = true)
    public List<ShareGrant> sharedWith(CurrentUser user) {
        return grants.sharedWith(permissions.principalIds(user), PageRequest.of(0, 200));
    }

    private Node editable(CurrentUser user, UUID nodeId) {
        Node node = content.get(user, nodeId);
        permissions.require(user, node, Right.EDIT_ITEMS);
        return node;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
