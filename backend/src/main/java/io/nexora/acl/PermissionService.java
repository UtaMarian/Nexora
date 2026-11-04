package io.nexora.acl;

import io.nexora.common.ApiException;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.GroupMember;
import io.nexora.identity.GroupMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The single place where access decisions are made. API, search and (later) WebDAV all call this.
 *
 * <p>Semantics follow SharePoint: a node either has unique permissions (its own ACL) or inherits
 * from its parent. Sites always have unique permissions. A user's effective level is the highest
 * level granted to the user or to any group they belong to (groups may nest).
 */
@Service
@Transactional(readOnly = true)
public class PermissionService {
    private final NodeRepository nodes;
    private final AclEntryRepository acl;
    private final GroupMemberRepository members;

    public PermissionService(NodeRepository nodes, AclEntryRepository acl, GroupMemberRepository members) {
        this.nodes = nodes;
        this.acl = acl;
        this.members = members;
    }

    /** The user's id plus every group they are a member of, transitively. */
    public Set<UUID> principalIds(CurrentUser user) {
        Set<UUID> result = new HashSet<>();
        result.add(user.id());
        Set<UUID> frontier = Set.of(user.id());
        while (!frontier.isEmpty()) {
            Set<UUID> next = new HashSet<>();
            for (GroupMember gm : members.findByMemberIdIn(frontier)) {
                if (result.add(gm.getGroupId())) next.add(gm.getGroupId());
            }
            frontier = next;
        }
        return result;
    }

    /** Walks up the tree to the nearest node with unique permissions. */
    public Node aclSource(Node node) {
        Node current = node;
        int guard = 0;
        while (current.isInheritsPermissions() && current.getParentId() != null) {
            current = nodes.findById(current.getParentId())
                    .orElseThrow(() -> new IllegalStateException("Broken tree at " + node.getPath()));
            if (++guard > 1000) throw new IllegalStateException("Tree too deep or cyclic at " + node.getPath());
        }
        return current;
    }

    /** Highest level the user holds on the node, or {@code null} for no access. */
    public PermissionLevel effectiveLevel(CurrentUser user, Node node) {
        if (user.admin()) return PermissionLevel.FULL_CONTROL;
        return levelOnSource(aclSource(node), principalIds(user));
    }

    public boolean has(CurrentUser user, Node node, Right right) {
        PermissionLevel level = effectiveLevel(user, node);
        return level != null && level.grants(right);
    }

    /**
     * Throws unless the user holds {@code right}. Users who cannot even see the node get a 404,
     * so the API does not reveal that it exists.
     */
    public PermissionLevel require(CurrentUser user, Node node, Right right) {
        PermissionLevel level = effectiveLevel(user, node);
        if (level == null) throw ApiException.notFound("Item");
        if (!level.grants(right)) {
            throw ApiException.forbidden("You need the " + right + " permission on '" + node.getName() + "'");
        }
        return level;
    }

    /**
     * Effective level per node, resolving each distinct ACL source only once.
     * Nodes the user cannot access are absent from the map.
     */
    public Map<UUID, PermissionLevel> levels(CurrentUser user, Collection<Node> candidates) {
        Map<UUID, PermissionLevel> result = new HashMap<>();
        if (user.admin()) {
            candidates.forEach(n -> result.put(n.getId(), PermissionLevel.FULL_CONTROL));
            return result;
        }
        Set<UUID> principals = principalIds(user);
        Map<UUID, Optional<PermissionLevel>> bySource = new HashMap<>();
        for (Node n : candidates) {
            Node source = aclSource(n);
            bySource.computeIfAbsent(source.getId(), k -> Optional.ofNullable(levelOnSource(source, principals)))
                    .ifPresent(level -> result.put(n.getId(), level));
        }
        return result;
    }

    /** The ACL sources the user is on: what search filters by, so a grant or revoke applies to the next query. */
    public Set<UUID> readableSources(CurrentUser user) {
        return acl.findNodeIdsByPrincipals(principalIds(user));
    }

    /** Security-trims a list of nodes, keeping order. */
    public List<Node> filterVisible(CurrentUser user, List<Node> candidates) {
        Map<UUID, PermissionLevel> levels = levels(user, candidates);
        return candidates.stream().filter(n -> levels.containsKey(n.getId())).toList();
    }

    private PermissionLevel levelOnSource(Node source, Set<UUID> principals) {
        PermissionLevel best = null;
        for (AclEntry e : acl.findByNodeIdAndPrincipalIdIn(source.getId(), principals)) {
            best = PermissionLevel.max(best, e.getLevel());
        }
        return best;
    }

    // ---- mutations -------------------------------------------------------------------------

    /** Gives a node unique permissions, starting from a copy of what it inherited (SharePoint default). */
    @Transactional
    public void breakInheritance(Node node, boolean copyParentEntries) {
        if (!node.isInheritsPermissions()) return;
        Node source = aclSource(node);
        node.setInheritsPermissions(false);
        nodes.save(node);
        if (copyParentEntries) {
            for (AclEntry e : acl.findByNodeId(source.getId())) {
                acl.save(new AclEntry(node.getId(), e.getPrincipalId(), e.getLevel()));
            }
        }
    }

    /** Drops the node's unique permissions so it inherits from its parent again. */
    @Transactional
    public void resetInheritance(Node node) {
        if (node.getKind() == Node.Kind.SITE) {
            throw ApiException.badRequest("site_is_root", "Sites always have unique permissions");
        }
        acl.deleteByNodeId(node.getId());
        node.setInheritsPermissions(true);
        nodes.save(node);
    }

    @Transactional
    public void grant(Node node, UUID principalId, PermissionLevel level) {
        if (node.isInheritsPermissions()) {
            throw ApiException.conflict("inherits_permissions",
                    "This item inherits permissions. Stop inheriting before granting access directly.");
        }
        AclEntry entry = acl.findByNodeIdAndPrincipalId(node.getId(), principalId)
                .orElseGet(() -> new AclEntry(node.getId(), principalId, level));
        entry.setLevel(level);
        acl.save(entry);
    }

    @Transactional
    public void revoke(Node node, UUID principalId) {
        if (node.isInheritsPermissions()) {
            throw ApiException.conflict("inherits_permissions", "This item inherits permissions.");
        }
        acl.findByNodeIdAndPrincipalId(node.getId(), principalId).ifPresent(acl::delete);
    }

    public List<AclEntry> entries(Node source) {
        return acl.findByNodeId(source.getId());
    }
}
