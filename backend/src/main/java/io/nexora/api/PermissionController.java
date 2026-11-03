package io.nexora.api;

import io.nexora.acl.AclEntry;
import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.api.Dtos.*;
import io.nexora.audit.AuditService;
import io.nexora.content.ContentService;
import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.IdentityService;
import io.nexora.identity.Principal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** "Manage access": inheritance and direct grants on a node. Requires Full Control (MANAGE_PERMISSIONS). */
@RestController
@RequestMapping("/api/v1/nodes/{id}/permissions")
public class PermissionController {
    private final ContentService content;
    private final PermissionService permissions;
    private final IdentityService identity;
    private final AuditService audit;

    public PermissionController(ContentService content, PermissionService permissions, IdentityService identity,
                                AuditService audit) {
        this.content = content;
        this.permissions = permissions;
        this.identity = identity;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PermissionsDto get(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        Node node = manageable(user, id);
        return view(node);
    }

    @PostMapping("/break-inheritance")
    @Transactional
    public PermissionsDto breakInheritance(@PathVariable UUID id,
                                           @RequestBody(required = false) BreakInheritanceRequest body) {
        CurrentUser user = CurrentUser.get();
        Node node = manageable(user, id);
        boolean copy = body == null || body.copyParentPermissions() == null || body.copyParentPermissions();
        permissions.breakInheritance(node, copy);
        audit.record(user, "PERMISSIONS_BREAK_INHERITANCE", node, Map.of("copied", copy));
        return view(node);
    }

    @PostMapping("/reset-inheritance")
    @Transactional
    public PermissionsDto resetInheritance(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        Node node = manageable(user, id);
        permissions.resetInheritance(node);
        audit.record(user, "PERMISSIONS_RESET_INHERITANCE", node);
        return view(node);
    }

    @PutMapping("/{principalId}")
    @Transactional
    public PermissionsDto grant(@PathVariable UUID id, @PathVariable UUID principalId,
                                @Valid @RequestBody GrantRequest body) {
        CurrentUser user = CurrentUser.get();
        Node node = manageable(user, id);
        Principal p = identity.get(principalId);
        permissions.grant(node, p.getId(), body.level());
        audit.record(user, "PERMISSIONS_GRANT", node, Map.of("principal", p.getLogin(), "level", body.level().name()));
        return view(node);
    }

    @DeleteMapping("/{principalId}")
    @ResponseStatus(HttpStatus.OK)
    @Transactional
    public PermissionsDto revoke(@PathVariable UUID id, @PathVariable UUID principalId) {
        CurrentUser user = CurrentUser.get();
        Node node = manageable(user, id);
        permissions.revoke(node, principalId);
        audit.record(user, "PERMISSIONS_REVOKE", node, Map.of("principal", principalId.toString()));
        return view(node);
    }

    private Node manageable(CurrentUser user, UUID id) {
        Node node = content.get(user, id);
        permissions.require(user, node, Right.MANAGE_PERMISSIONS);
        return node;
    }

    private PermissionsDto view(Node node) {
        Node source = permissions.aclSource(node);
        List<AclEntry> entries = permissions.entries(source);
        Map<UUID, Principal> people = identity.byIds(entries.stream().map(AclEntry::getPrincipalId).toList());
        List<AclEntryDto> dtos = entries.stream()
                .filter(e -> people.containsKey(e.getPrincipalId()))
                .map(e -> new AclEntryDto(PrincipalRef.of(people.get(e.getPrincipalId())), e.getLevel()))
                .sorted(Comparator.comparing((AclEntryDto e) -> e.level().ordinal()).reversed()
                        .thenComparing(e -> e.principal().displayName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new PermissionsDto(node.isInheritsPermissions(), source.getId(), source.getPath(), dtos);
    }
}
