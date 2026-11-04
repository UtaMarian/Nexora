package io.nexora.api;

import io.nexora.acl.PermissionLevel;
import io.nexora.acl.PermissionService;
import io.nexora.api.Dtos.NodeDto;
import io.nexora.api.Dtos.PrincipalRef;
import io.nexora.content.EditLock;
import io.nexora.content.EditLockService;
import io.nexora.content.FileVersion;
import io.nexora.content.FileVersionRepository;
import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.IdentityService;
import io.nexora.identity.Principal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Converts nodes to DTOs in batches (one query each for authors, versions and the caller's rights). */
@Component
public class NodeMapper {
    private final IdentityService identity;
    private final FileVersionRepository versions;
    private final PermissionService permissions;
    private final EditLockService editLocks;
    private final io.nexora.workflow.ApprovalRepository approvals;

    public NodeMapper(IdentityService identity, FileVersionRepository versions, PermissionService permissions,
                      EditLockService editLocks, io.nexora.workflow.ApprovalRepository approvals) {
        this.approvals = approvals;
        this.editLocks = editLocks;
        this.identity = identity;
        this.versions = versions;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public PrincipalRef ref(UUID id) {
        return id == null ? null : PrincipalRef.of(identity.get(id));
    }

    public NodeDto one(CurrentUser user, Node node) {
        return many(user, List.of(node)).get(0);
    }

    @Transactional(readOnly = true)
    public List<NodeDto> many(CurrentUser user, List<Node> nodes) {
        Set<UUID> principalIds = new HashSet<>();
        Set<UUID> versionIds = new HashSet<>();
        for (Node n : nodes) {
            principalIds.add(n.getCreatedBy());
            principalIds.add(n.getModifiedBy());
            if (n.getCheckedOutBy() != null) principalIds.add(n.getCheckedOutBy());
            if (n.getCurrentVersionId() != null) versionIds.add(n.getCurrentVersionId());
        }
        Map<UUID, EditLock> locks = editLocks.activeFor(
                nodes.stream().filter(n -> n.getKind() == Node.Kind.FILE).map(Node::getId).toList());
        locks.values().forEach(l -> principalIds.add(l.getOwnerId()));
        Map<UUID, Principal> people = identity.byIds(principalIds);
        Map<UUID, String> labels = new HashMap<>();
        versions.findAllById(versionIds).forEach(v -> labels.put(v.getId(), v.label()));
        Map<UUID, PermissionLevel> levels = permissions.levels(user, nodes);
        // the latest request decides the badge
        Map<UUID, io.nexora.workflow.Approval> latest = new HashMap<>();
        List<UUID> approvable = nodes.stream().filter(n -> n.getKind() == Node.Kind.FILE || n.getKind() == Node.Kind.LIST_ITEM).map(Node::getId).toList();
        if (!approvable.isEmpty()) {
            approvals.findByNodeIdIn(approvable).forEach(a -> latest.merge(a.getNodeId(), a, (x, y) -> x.getCreatedAt().isAfter(y.getCreatedAt()) ? x : y));
        }

        List<NodeDto> out = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            PermissionLevel level = levels.get(n.getId());
            boolean mine = n.isCheckedOut() && n.getCheckedOutBy().equals(user.id());
            out.add(new NodeDto(
                    n.getId(), n.getKind(), n.getName(), n.getTitle(), n.getDescription(), n.getPath(),
                    n.getParentId(), n.getSiteId(),
                    mine && n.getWorkingSizeBytes() != null ? n.getWorkingSizeBytes() : n.getSizeBytes(),
                    n.getMimeType(),
                    n.getCurrentVersionId() == null ? null : labels.get(n.getCurrentVersionId()),
                    PrincipalRef.of(people.get(n.getCheckedOutBy())), n.getCheckedOutAt(),
                    mine && n.getWorkingStorageKey() != null,
                    locks.containsKey(n.getId()) ? PrincipalRef.of(people.get(locks.get(n.getId()).getOwnerId())) : null,
                    locks.containsKey(n.getId()) ? locks.get(n.getId()).getExpiresAt() : null,
                    PrincipalRef.of(people.get(n.getCreatedBy())), n.getCreatedAt(),
                    PrincipalRef.of(people.get(n.getModifiedBy())), n.getModifiedAt(),
                    n.getMetadata(), n.isInheritsPermissions(),
                    level, level == null ? Set.of() : level.rights(),
                    latest.containsKey(n.getId()) ? latest.get(n.getId()).getKind() + ":" + latest.get(n.getId()).getStatus() : null));
        }
        return out;
    }

    public List<Dtos.VersionDto> versions(Node file, List<FileVersion> history) {
        Map<UUID, Principal> people = identity.byIds(history.stream().map(FileVersion::getCreatedBy).toList());
        return history.stream()
                .map(v -> Dtos.VersionDto.of(v, file.getCurrentVersionId(), people.get(v.getCreatedBy())))
                .toList();
    }
}
