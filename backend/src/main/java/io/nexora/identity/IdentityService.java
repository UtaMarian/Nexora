package io.nexora.identity;

import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

/** Local users, groups and group membership. Directory sync (AD/LDAP via Keycloak) will feed the same tables. */
@Service
@Transactional
public class IdentityService {
    private static final Pattern LOGIN = Pattern.compile("^[A-Za-z0-9._@-]{2,128}$");

    private final PrincipalRepository principals;
    private final GroupMemberRepository members;
    private final NodeRepository nodes;
    private final PermissionService permissions;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public IdentityService(PrincipalRepository principals, GroupMemberRepository members, NodeRepository nodes,
                           PermissionService permissions, PasswordEncoder encoder, AuditService audit) {
        this.principals = principals;
        this.members = members;
        this.nodes = nodes;
        this.permissions = permissions;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Principal get(UUID id) {
        return principals.findById(id).orElseThrow(() -> ApiException.notFound("User or group"));
    }

    @Transactional(readOnly = true)
    public Map<UUID, Principal> byIds(Collection<UUID> ids) {
        Map<UUID, Principal> map = new HashMap<>();
        principals.findAllById(new HashSet<>(ids)).forEach(p -> map.put(p.getId(), p));
        return map;
    }

    @Transactional(readOnly = true)
    public List<Principal> search(String q) {
        String term = q == null ? "" : q.strip();
        return principals.search(term).stream().filter(Principal::isEnabled).limit(50).toList();
    }

    @Transactional(readOnly = true)
    public List<Principal> siteGroups(CurrentUser user, UUID siteId) {
        Node site = nodes.findLive(siteId).filter(n -> n.getKind() == Node.Kind.SITE)
                .orElseThrow(() -> ApiException.notFound("Site"));
        permissions.require(user, site, Right.VIEW);
        return principals.findBySiteId(siteId);
    }

    @Transactional(readOnly = true)
    public List<Principal> members(UUID groupId) {
        Principal group = requireGroup(groupId);
        List<UUID> ids = members.findByGroupId(group.getId()).stream().map(GroupMember::getMemberId).toList();
        return principals.findAllById(ids).stream()
                .sorted(Comparator.comparing(Principal::getDisplayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public void addMember(CurrentUser user, UUID groupId, UUID memberId) {
        Principal group = requireGroup(groupId);
        requireCanManage(user, group);
        Principal member = get(memberId);
        if (member.getId().equals(group.getId()) || createsCycle(group.getId(), member.getId())) {
            throw ApiException.badRequest("group_cycle", "A group cannot contain itself");
        }
        if (!members.existsByGroupIdAndMemberId(groupId, memberId)) {
            members.save(new GroupMember(groupId, memberId));
            audit.record(user, "GROUP_MEMBER_ADD", siteNode(group),
                    Map.of("group", group.getLogin(), "member", member.getLogin()));
        }
    }

    public void removeMember(CurrentUser user, UUID groupId, UUID memberId) {
        Principal group = requireGroup(groupId);
        requireCanManage(user, group);
        members.findById(new GroupMember.Key(groupId, memberId)).ifPresent(gm -> {
            members.delete(gm);
            audit.record(user, "GROUP_MEMBER_REMOVE", siteNode(group),
                    Map.of("group", group.getLogin(), "member", memberId.toString()));
        });
    }

    public Principal createUser(CurrentUser actor, String login, String displayName, String email, String password,
                                boolean admin) {
        if (!actor.admin()) throw ApiException.forbidden("Only administrators can create users");
        if (login == null || !LOGIN.matcher(login).matches()) {
            throw ApiException.badRequest("invalid_login", "Login must be 2-128 letters, digits or . _ @ -");
        }
        if (principals.findByKindAndLogin(Principal.Kind.USER, login).isPresent()) {
            throw ApiException.conflict("login_taken", "A user with that login already exists");
        }
        if (password == null || password.length() < 8) {
            throw ApiException.badRequest("weak_password", "Password must be at least 8 characters");
        }
        Principal p = Principal.user(login, displayName == null || displayName.isBlank() ? login : displayName.strip(),
                encoder.encode(password));
        p.setEmail(email);
        p.setAdmin(admin);
        principals.save(p);
        audit.record(actor, "USER_CREATE", null, Map.of("login", login, "admin", admin));
        return p;
    }

    public Principal createGroup(CurrentUser actor, String name, String displayName) {
        if (!actor.admin()) throw ApiException.forbidden("Only administrators can create global groups");
        if (name == null || !LOGIN.matcher(name).matches()) {
            throw ApiException.badRequest("invalid_name", "Group name must be 2-128 letters, digits or . _ @ -");
        }
        if (principals.findByKindAndLogin(Principal.Kind.GROUP, name).isPresent()) {
            throw ApiException.conflict("group_exists", "A group with that name already exists");
        }
        Principal g = principals.save(Principal.group(name, displayName == null || displayName.isBlank()
                ? name : displayName.strip(), null));
        audit.record(actor, "GROUP_CREATE", null, Map.of("group", name));
        return g;
    }

    private Principal requireGroup(UUID id) {
        Principal p = get(id);
        if (!p.isGroup()) throw ApiException.badRequest("not_a_group", "Not a group");
        return p;
    }

    private void requireCanManage(CurrentUser user, Principal group) {
        if (user.admin()) return;
        Node site = siteNode(group);
        if (site == null || !permissions.has(user, site, Right.MANAGE_WEB)) {
            throw ApiException.forbidden("You cannot manage this group");
        }
    }

    private Node siteNode(Principal group) {
        return group.getSiteId() == null ? null : nodes.findLive(group.getSiteId()).orElse(null);
    }

    /** True if {@code groupId} is (transitively) a member of {@code candidateMemberId}. */
    private boolean createsCycle(UUID groupId, UUID candidateMemberId) {
        Deque<UUID> stack = new ArrayDeque<>(List.of(candidateMemberId));
        Set<UUID> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            UUID g = stack.pop();
            if (!seen.add(g)) continue;
            for (GroupMember gm : members.findByGroupId(g)) {
                if (gm.getMemberId().equals(groupId)) return true;
                stack.push(gm.getMemberId());
            }
        }
        return false;
    }
}
