package io.nexora.identity.sso;

import io.nexora.audit.AuditService;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.GroupMember;
import io.nexora.identity.GroupMemberRepository;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Creates and updates NEXORA principals from directory identities (Keycloak, which federates AD/LDAP and
 * brokers ADFS). Directory principals carry an {@code externalId}:
 * <ul>
 *   <li>{@code kc:<keycloak user id>} for users,</li>
 *   <li>{@code kc-group:<group path>} for Keycloak/AD groups (hierarchy mirrored as group-in-group),</li>
 *   <li>{@code idp-group:<name>} for groups asserted by a brokered provider such as ADFS.</li>
 * </ul>
 * Membership in directory groups is owned by the directory: it is replaced on every sign-in and sync. Site
 * groups (Owners/Members/Visitors) are owned by NEXORA and never touched here.
 */
@Service
@Transactional
public class DirectoryProvisioner {
    private static final Logger log = LoggerFactory.getLogger(DirectoryProvisioner.class);
    public static final String USER_PREFIX = "kc:";
    public static final String GROUP_PREFIX = "kc-group:";
    public static final String IDP_GROUP_PREFIX = "idp-group:";

    private final PrincipalRepository principals;
    private final GroupMemberRepository members;
    private final AuditService audit;
    private final List<String> adminGroups;

    public DirectoryProvisioner(PrincipalRepository principals, GroupMemberRepository members, AuditService audit,
                                IdentityProperties props) {
        this.principals = principals;
        this.members = members;
        this.audit = audit;
        this.adminGroups = props.oidc().adminGroups().stream().map(g -> g.strip().toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * @param subject        stable id from the provider (Keycloak user id)
     * @param groupPaths     Keycloak group paths, e.g. "/Engineering/Backend"; null leaves memberships unchanged
     * @param providerGroups group names asserted by a brokered IdP (ADFS); null leaves them unchanged
     */
    public record DirectoryUser(String subject, String login, String displayName, String email, boolean enabled,
                                Collection<String> groupPaths, Collection<String> providerGroups) {}

    /** Thrown when a directory user would take over an existing local account. */
    public static class AccountConflictException extends RuntimeException {
        public AccountConflictException(String message) { super(message); }
    }

    public Principal provision(DirectoryUser u) {
        if (u.subject() == null || u.subject().isBlank()) throw new IllegalArgumentException("Directory user without subject");
        String externalId = USER_PREFIX + u.subject();
        String login = u.login() == null || u.login().isBlank() ? u.subject() : u.login().strip();

        Principal p = principals.findByExternalId(externalId).orElse(null);
        boolean created = false;
        if (p == null) {
            Principal sameLogin = principals.findByKindAndLogin(Principal.Kind.USER, login).orElse(null);
            if (sameLogin != null) {
                if (sameLogin.getExternalId() != null || sameLogin.getPasswordHash() != null) {
                    throw new AccountConflictException("The user name '" + login
                            + "' already belongs to another account. Ask an administrator to rename or remove it.");
                }
                p = sameLogin;   // a pre-created, password-less placeholder: adopt it
            } else {
                p = Principal.user(login, display(u, login), null);
                created = true;
            }
            p.setExternalId(externalId);
        } else if (!p.getLogin().equalsIgnoreCase(login)
                && principals.findByKindAndLogin(Principal.Kind.USER, login).isEmpty()) {
            p.setLogin(login);  // renamed in the directory
        }
        p.setDisplayName(display(u, login));
        p.setEmail(u.email());
        p.setEnabled(u.enabled());
        principals.save(p);

        if (u.groupPaths() != null || u.providerGroups() != null) {
            Set<UUID> desired = new HashSet<>();
            if (u.groupPaths() != null) {
                for (String path : u.groupPaths()) {
                    if (path == null || path.isBlank()) continue;
                    String norm = normalizePath(path);
                    desired.add(ensureGroupPath(norm).getId());
                }
            }
            if (u.providerGroups() != null) {
                for (String name : u.providerGroups()) {
                    if (name == null || name.isBlank()) continue;
                    desired.add(ensureProviderGroup(name.strip()).getId());
                }
            }
            replaceDirectoryMemberships(p, desired, u.groupPaths() != null, u.providerGroups() != null);
            if (!adminGroups.isEmpty()) p.setAdmin(directoryGroupNames(p).stream().anyMatch(adminGroups::contains));
        }
        if (created) audit.record(CurrentUser.system(p), "DIRECTORY_USER_CREATE", null, Map.of("login", login));
        return p;
    }

    /** Ensures the group (and its parents) exist, with each child a member of its parent. */
    public Principal ensureGroupPath(String path) {
        String norm = normalizePath(path);
        Principal group = principals.findByExternalId(GROUP_PREFIX + norm).orElse(null);
        if (group == null) {
            group = principals.save(newGroup(norm, lastSegment(norm), GROUP_PREFIX + norm));
        } else if (!group.isEnabled()) {
            group.setEnabled(true);
        }
        int slash = norm.lastIndexOf('/');
        if (slash > 0) {
            Principal parent = ensureGroupPath(norm.substring(0, slash));
            if (!members.existsByGroupIdAndMemberId(parent.getId(), group.getId())) {
                members.save(new GroupMember(parent.getId(), group.getId()));
            }
        }
        return group;
    }

    public Principal ensureProviderGroup(String name) {
        return principals.findByExternalId(IDP_GROUP_PREFIX + name)
                .orElseGet(() -> principals.save(newGroup("idp:" + name, name, IDP_GROUP_PREFIX + name)));
    }

    /** Replaces the user's memberships in directory-owned groups; NEXORA site groups are left alone. */
    private void replaceDirectoryMemberships(Principal user, Set<UUID> desired, boolean manageKc, boolean manageIdp) {
        List<GroupMember> current = members.findByMemberId(user.getId());
        Map<UUID, Principal> groups = new HashMap<>();
        principals.findAllById(current.stream().map(GroupMember::getGroupId).toList()).forEach(g -> groups.put(g.getId(), g));
        Set<UUID> have = new HashSet<>();
        for (GroupMember gm : current) {
            Principal g = groups.get(gm.getGroupId());
            String ext = g == null || g.getExternalId() == null ? "" : g.getExternalId();
            boolean owned = (manageKc && ext.startsWith(GROUP_PREFIX)) || (manageIdp && ext.startsWith(IDP_GROUP_PREFIX));
            if (!owned) continue;
            if (desired.contains(gm.getGroupId())) have.add(gm.getGroupId());
            else members.delete(gm);
        }
        for (UUID g : desired) {
            if (!have.contains(g)) members.save(new GroupMember(g, user.getId()));
        }
    }

    /** Disables directory users and groups the directory no longer has, and empties those groups. */
    public int retire(Set<String> liveUserIds, Set<String> liveGroupPaths) {
        int changed = 0;
        for (Principal p : principals.findByExternalIdPrefix(USER_PREFIX)) {
            if (p.isEnabled() && !liveUserIds.contains(p.getExternalId().substring(USER_PREFIX.length()))) {
                p.setEnabled(false);
                changed++;
            }
        }
        for (Principal g : principals.findByExternalIdPrefix(GROUP_PREFIX)) {
            if (g.isEnabled() && !liveGroupPaths.contains(g.getExternalId().substring(GROUP_PREFIX.length()))) {
                g.setEnabled(false);
                members.deleteAll(members.findByGroupId(g.getId()));
                changed++;
            }
        }
        if (changed > 0) log.info("Directory sync retired {} users/groups", changed);
        return changed;
    }

    /** Paths and names (lower-case) of every directory group the user is in, from any source. */
    private Set<String> directoryGroupNames(Principal user) {
        Set<String> out = new HashSet<>();
        List<UUID> ids = members.findByMemberId(user.getId()).stream().map(GroupMember::getGroupId).toList();
        for (Principal g : principals.findAllById(ids)) {
            String ext = g.getExternalId();
            if (ext == null) continue;
            if (ext.startsWith(GROUP_PREFIX)) {
                String path = ext.substring(GROUP_PREFIX.length());
                out.add(path.toLowerCase(Locale.ROOT));
                out.add(lastSegment(path).toLowerCase(Locale.ROOT));
            } else if (ext.startsWith(IDP_GROUP_PREFIX)) {
                out.add(ext.substring(IDP_GROUP_PREFIX.length()).toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private Principal newGroup(String login, String displayName, String externalId) {
        String candidate = login;
        for (int i = 2; principals.findByKindAndLogin(Principal.Kind.GROUP, candidate).isPresent(); i++) {
            candidate = login + " (" + i + ")";
        }
        Principal g = Principal.group(candidate, displayName, null);
        g.setExternalId(externalId);
        return g;
    }

    private static String display(DirectoryUser u, String login) {
        return u.displayName() == null || u.displayName().isBlank() ? login : u.displayName().strip();
    }

    static String normalizePath(String path) {
        String p = path.strip();
        if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
