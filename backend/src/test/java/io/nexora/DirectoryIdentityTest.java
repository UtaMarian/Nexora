package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.identity.GroupMemberRepository;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.identity.sso.DirectoryProvisioner;
import io.nexora.identity.sso.DirectoryProvisioner.DirectoryUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Directory (AD/LDAP via Keycloak, ADFS) users and groups mapped onto NEXORA principals. */
class DirectoryIdentityTest extends ApiTestSupport {
    @Autowired DirectoryProvisioner provisioner;
    @Autowired PrincipalRepository principals;
    @Autowired GroupMemberRepository members;
    @Autowired TransactionTemplate tx;

    private Principal provision(String sub, String login, List<String> groups, List<String> idpGroups) {
        return tx.execute(t -> provisioner.provision(new DirectoryUser(sub, login, "Dir " + login, login + "@corp.test",
                true, groups, idpGroups)));
    }

    private Set<String> groupLogins(Principal user) {
        return tx.execute(t -> {
            var ids = members.findByMemberId(user.getId()).stream().map(m -> m.getGroupId()).toList();
            return principals.findAllById(ids).stream().map(Principal::getLogin).collect(java.util.stream.Collectors.toSet());
        });
    }

    @Test
    void usersAndGroupHierarchyAreProvisioned() {
        String sub = UUID.randomUUID().toString();
        String eng = "/Eng-" + suffix;
        Principal u = provision(sub, "jdoe-" + suffix, List.of(eng + "/Backend"), List.of());
        assertThat(u.getExternalId()).isEqualTo("kc:" + sub);
        assertThat(u.getPasswordHash()).isNull();
        assertThat(groupLogins(u)).containsExactly(eng + "/Backend");

        Principal backend = principals.findByExternalId("kc-group:" + eng + "/Backend").orElseThrow();
        Principal parent = principals.findByExternalId("kc-group:" + eng).orElseThrow();
        assertThat(backend.getDisplayName()).isEqualTo("Backend");
        assertThat(members.existsByGroupIdAndMemberId(parent.getId(), backend.getId())).isTrue();

        // second sign-in: same principal, memberships follow the directory
        Principal again = provision(sub, "jdoe-" + suffix, List.of(eng), List.of());
        assertThat(again.getId()).isEqualTo(u.getId());
        assertThat(groupLogins(again)).containsExactly(eng);
    }

    @Test
    void directoryGroupsGrantSitePermissionsThroughNesting() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "ad-" + suffix));
        String eng = "/Eng-" + suffix;
        Principal user = provision(UUID.randomUUID().toString(), "ana-" + suffix, List.of(eng + "/Frontend"), List.of());
        Principal engGroup = principals.findByExternalId("kc-group:" + eng).orElseThrow();

        // give the whole AD group read access by adding it to the site's Visitors
        addGroupToGroup(siteGroup(site, "visitors"), engGroup.getId());

        var asUser = SecurityMockMvcRequestPostProcessors.user(new io.nexora.identity.NexoraUserDetails(user));
        int status = mvc.perform(get("/api/v1/nodes/{id}", site).with(asUser)).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);

        // leaving the AD group removes access at the next sign-in or sync
        provision(user.getExternalId().substring(3), "ana-" + suffix, List.of(), List.of());
        status = mvc.perform(get("/api/v1/nodes/{id}", site).with(asUser)).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(404);
    }

    @Test
    void siteGroupMembershipsSurviveDirectoryUpdates() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "keep-" + suffix));
        Principal user = provision(UUID.randomUUID().toString(), "keep-" + suffix, List.of("/Staff-" + suffix), List.of());
        addGroupToGroup(siteGroup(site, "members"), user.getId());

        Principal after = provision(user.getExternalId().substring(3), "keep-" + suffix, List.of(), List.of());
        assertThat(groupLogins(after)).containsExactly("keep-" + suffix + "-members");
    }

    @Test
    void adfsGroupsAndAdminMapping() {
        String sub = UUID.randomUUID().toString();
        Principal u = provision(sub, "boss-" + suffix, List.of("/NEXORA Admins"), List.of("Finance-" + suffix));
        assertThat(u.isAdmin()).isTrue();
        assertThat(groupLogins(u)).contains("/NEXORA Admins", "idp:Finance-" + suffix);

        // a background sync only knows Keycloak groups: ADFS groups stay, admin follows AD
        Principal synced = tx.execute(t -> provisioner.provision(new DirectoryUser(sub, "boss-" + suffix, null, null, true,
                List.of(), null)));
        assertThat(synced.isAdmin()).isFalse();
        assertThat(groupLogins(synced)).containsExactly("idp:Finance-" + suffix);
    }

    @Test
    void directoryUsersCannotTakeOverLocalAccounts() throws Exception {
        String local = createUser("local");
        assertThatThrownBy(() -> provision(UUID.randomUUID().toString(), local, List.of(), List.of()))
                .isInstanceOf(DirectoryProvisioner.AccountConflictException.class);
    }

    @Test
    void retiredUsersAreDisabled() {
        String sub = UUID.randomUUID().toString();
        Principal u = provision(sub, "leaver-" + suffix, List.of("/Gone-" + suffix), List.of());
        Set<String> otherUsers = tx.execute(t -> principals.findByExternalIdPrefix("kc:").stream()
                .map(p -> p.getExternalId().substring(3)).filter(id -> !id.equals(sub))
                .collect(java.util.stream.Collectors.toSet()));
        Set<String> otherGroups = tx.execute(t -> principals.findByExternalIdPrefix("kc-group:").stream()
                .map(p -> p.getExternalId().substring("kc-group:".length())).filter(p -> !p.equals("/Gone-" + suffix))
                .collect(java.util.stream.Collectors.toSet()));
        tx.executeWithoutResult(t -> provisioner.retire(otherUsers, otherGroups));
        assertThat(principals.findById(u.getId()).orElseThrow().isEnabled()).isFalse();
        assertThat(principals.findByExternalId("kc-group:/Gone-" + suffix).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void providersEndpointDescribesSignInOptions() throws Exception {
        JsonNode p = json.readTree(mvc.perform(get("/api/v1/auth/providers")).andReturn().getResponse().getContentAsString());
        assertThat(p.get("localLogin").asBoolean()).isTrue();
        assertThat(p.get("sso")).isEmpty();
    }

    private void addGroupToGroup(UUID group, UUID member) throws Exception {
        call(ADMIN, jsonBody(post("/api/v1/principals/{id}/members", group), Map.of("principalId", member)), 204);
    }
}
