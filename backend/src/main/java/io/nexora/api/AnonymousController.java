package io.nexora.api;

import io.nexora.acl.PermissionLevel;
import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.content.ContentService;
import io.nexora.content.Node;
import io.nexora.identity.AnonymousAccess;
import io.nexora.identity.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The administrator's switch for the farm, and each owner's choice for a site, library or list. */
@RestController
@RequestMapping("/api/v1")
public class AnonymousController {
    private static final Set<Node.Kind> OPENABLE = Set.of(Node.Kind.SITE, Node.Kind.LIBRARY, Node.Kind.LIST);

    public record Farm(boolean enabled) {}

    /** {@code available}: the farm allows it. {@code inherited}: it comes from {@code sourcePath}, and is changed there. */
    public record Status(boolean available, boolean enabled, boolean inherited, String sourcePath) {}

    public record Choice(boolean enabled) {}

    private final AnonymousAccess anonymous;
    private final PermissionService permissions;
    private final ContentService content;
    private final AuditService audit;

    public AnonymousController(AnonymousAccess anonymous, PermissionService permissions, ContentService content, AuditService audit) {
        this.anonymous = anonymous;
        this.permissions = permissions;
        this.content = content;
        this.audit = audit;
    }

    @GetMapping("/admin/anonymous")
    public Farm farm() {
        return new Farm(anonymous.enabled());
    }

    @PutMapping("/admin/anonymous")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void setFarm(@RequestBody Choice body) {
        CurrentUser user = CurrentUser.get();
        anonymous.set(body.enabled(), user.id());
        audit.record(user, body.enabled() ? "ANONYMOUS_FARM_ON" : "ANONYMOUS_FARM_OFF", null);
    }

    @GetMapping("/nodes/{id}/anonymous")
    public Status status(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        Node node = content.get(user, id);
        permissions.require(user, node, Right.MANAGE_PERMISSIONS);
        return status(node);
    }

    @PutMapping("/nodes/{id}/anonymous")
    @Transactional
    public Status set(@PathVariable UUID id, @RequestBody Choice body) {
        CurrentUser user = CurrentUser.get();
        Node node = content.get(user, id);
        permissions.require(user, node, Right.MANAGE_PERMISSIONS);
        if (!OPENABLE.contains(node.getKind())) {
            throw ApiException.badRequest("not_openable", "Anonymous access is chosen for a site, library or list");
        }
        if (body.enabled()) {
            if (!anonymous.enabled()) throw ApiException.conflict("anonymous_off", "An administrator has not allowed anonymous access");
            if (node.isInheritsPermissions() && !status(node).enabled()) permissions.breakInheritance(node, true);
            if (!node.isInheritsPermissions()) permissions.grant(node, anonymous.id(), PermissionLevel.READ);
        } else {
            if (node.isInheritsPermissions() && status(node).enabled()) {
                throw ApiException.conflict("inherited", "This comes from " + permissions.aclSource(node).getPath() + ". Change it there");
            }
            if (!node.isInheritsPermissions()) permissions.revoke(node, anonymous.id());
        }
        audit.record(user, body.enabled() ? "ANONYMOUS_ON" : "ANONYMOUS_OFF", node, Map.of("path", node.getPath()));
        return status(node);
    }

    private Status status(Node node) {
        Node source = permissions.aclSource(node);
        boolean open = permissions.entries(source).stream().anyMatch(e -> e.getPrincipalId().equals(anonymous.id()));
        return new Status(anonymous.enabled(), open, node.isInheritsPermissions(), source.getPath());
    }
}
