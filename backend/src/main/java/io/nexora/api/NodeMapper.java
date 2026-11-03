package io.nexora.api;

import io.nexora.acl.PermissionLevel;
import io.nexora.acl.PermissionService;
import io.nexora.api.Dtos.NodeDto;
import io.nexora.api.Dtos.PrincipalRef;
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

    public NodeMapper(IdentityService identity, FileVersionRepository versions, PermissionService permissions) {
        this.identity = identity;
        this.versions = versions;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
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
        Map<UUID, Principal> people = identity.byIds(principalIds);
        Map<UUID, String> labels = new HashMap<>();
        versions.findAllById(versionIds).forEach(v -> labels.put(v.getId(), v.label()));
        Map<UUID, PermissionLevel> levels = permissions.levels(user, nodes);

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
                    PrincipalRef.of(people.get(n.getCreatedBy())), n.getCreatedAt(),
                    PrincipalRef.of(people.get(n.getModifiedBy())), n.getModifiedAt(),
                    n.getMetadata(), n.isInheritsPermissions(),
                    level, level == null ? Set.of() : level.rights()));
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
