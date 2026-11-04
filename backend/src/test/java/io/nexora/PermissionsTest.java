package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class PermissionsTest extends ApiTestSupport {

    @Test
    void outsidersCannotSeeASiteAtAll() throws Exception {
        String owner = createUser("owner");
        String outsider = createUser("outsider");
        UUID site = id(createSite(owner, "private-" + suffix));

        call(outsider, get("/api/v1/nodes/{id}", site), 404);  // existence is not revealed
        JsonNode sites = call(outsider, get("/api/v1/sites"), 200);
        assertThat(sites).noneMatch(s -> s.get("id").asText().equals(site.toString()));
    }

    @Test
    void visitorsReadMembersEditOwnersManage() throws Exception {
        String owner = createUser("owner");
        String member = createUser("member");
        String visitor = createUser("visitor");
        UUID site = id(createSite(owner, "roles-" + suffix));
        addToGroup(siteGroup(site, "members"), member);
        addToGroup(siteGroup(site, "visitors"), visitor);
        UUID lib = defaultLibrary(owner, site);
        UUID file = id(upload(owner, lib, "Doc.txt", "hello", false, 201));

        assertThat(download(visitor, file)).isEqualTo("hello");
        upload(visitor, lib, "Mine.txt", "nope", false, 403);
        upload(visitor, lib, "Doc.txt", "nope", false, 403);   // not 409: names are not revealed to readers
        upload(visitor, lib, "Doc.txt", "nope", true, 403);
        call(visitor, delete("/api/v1/nodes/{id}", file), 403);

        upload(member, lib, "Mine.txt", "yes", false, 201);
        call(member, jsonBody(post("/api/v1/sites/{id}/libraries", site), Map.of("name", "Projects")), 201); // Edit = manage lists
        call(member, get("/api/v1/nodes/{id}/permissions", lib), 403);                                       // but not permissions

        JsonNode perms = call(owner, get("/api/v1/nodes/{id}/permissions", lib), 200);
        assertThat(perms.get("inheritsPermissions").asBoolean()).isTrue();
        assertThat(perms.get("entries")).hasSize(3);
    }

    @Test
    void breakingInheritanceHidesAFolderFromVisitors() throws Exception {
        String owner = createUser("owner");
        String visitor = createUser("visitor");
        String auditor = createUser("auditor");
        UUID site = id(createSite(owner, "hr-" + suffix));
        UUID visitors = siteGroup(site, "visitors");
        addToGroup(visitors, visitor);
        addToGroup(visitors, auditor);
        UUID lib = defaultLibrary(owner, site);
        UUID secret = id(call(owner, jsonBody(post("/api/v1/nodes/{id}/folders", lib), Map.of("name", "Salaries")), 201));
        UUID open = id(call(owner, jsonBody(post("/api/v1/nodes/{id}/folders", lib), Map.of("name", "Handbook")), 201));
        UUID payroll = id(upload(owner, secret, "payroll.csv", "a,b", false, 201));

        // granting directly on an inheriting node is refused
        call(owner, jsonBody(put("/api/v1/nodes/{id}/permissions/{p}", secret, principalId(auditor)),
                Map.of("level", "READ")), 409);

        JsonNode broken = call(owner, post("/api/v1/nodes/{id}/permissions/break-inheritance", secret), 200);
        assertThat(broken.get("inheritsPermissions").asBoolean()).isFalse();
        assertThat(broken.get("entries")).hasSize(3); // copied from the site
        call(owner, delete("/api/v1/nodes/{id}/permissions/{p}", secret, visitors), 200);
        call(owner, jsonBody(put("/api/v1/nodes/{id}/permissions/{p}", secret, principalId(auditor)),
                Map.of("level", "READ")), 200);

        JsonNode visible = call(visitor, get("/api/v1/nodes/{id}/children", lib), 200);
        assertThat(visible).extracting(n -> n.get("name").asText()).containsExactly("Handbook");
        call(visitor, get("/api/v1/nodes/{id}", payroll), 404);
        call(visitor, get("/api/v1/nodes/{id}/content", payroll), 404);
        assertThat(call(visitor, get("/api/v1/search").param("q", "payroll"), 200).get("hits")).isEmpty();

        // the auditor has a direct grant
        assertThat(download(auditor, payroll)).isEqualTo("a,b");
        assertThat(call(auditor, get("/api/v1/nodes/{id}/children", lib), 200)).hasSize(2);

        // reset inheritance gives everyone back the site permissions
        call(owner, post("/api/v1/nodes/{id}/permissions/reset-inheritance", secret), 200);
        assertThat(download(visitor, payroll)).isEqualTo("a,b");
        assertThat(call(visitor, get("/api/v1/nodes/{id}", open), 200)).isNotNull();
    }

    @Test
    void nestedGroupsGrantAccess() throws Exception {
        String owner = createUser("owner");
        String user = createUser("nested");
        UUID site = id(createSite(owner, "nest-" + suffix));
        JsonNode dept = call(ADMIN, jsonBody(post("/api/v1/admin/groups"), Map.of("name", "dept-" + suffix)), 201);
        addToGroup(id(dept), user);
        call(ADMIN, jsonBody(post("/api/v1/principals/{id}/members", siteGroup(site, "visitors")),
                Map.of("principalId", id(dept))), 204);

        assertThat(call(user, get("/api/v1/nodes/{id}", site), 200).get("node").get("myLevel").asText()).isEqualTo("READ");

        // cycles are refused
        call(ADMIN, jsonBody(post("/api/v1/principals/{id}/members", id(dept)),
                Map.of("principalId", siteGroup(site, "visitors"))), 400);
    }

    @Test
    void auditLogRecordsActivityAndIsRestricted() throws Exception {
        String owner = createUser("owner");
        String member = createUser("member");
        UUID site = id(createSite(owner, "audit-" + suffix));
        addToGroup(siteGroup(site, "members"), member);
        UUID lib = defaultLibrary(owner, site);
        UUID file = id(upload(owner, lib, "a.txt", "x", false, 201));
        download(member, file);

        JsonNode log = call(owner, get("/api/v1/audit").param("siteId", site.toString()), 200);
        assertThat(log.get("items")).extracting(e -> e.get("action").asText())
                .contains("SITE_CREATE", "FILE_UPLOAD", "FILE_DOWNLOAD", "GROUP_MEMBER_ADD");

        call(member, get("/api/v1/audit").param("siteId", site.toString()), 403);
        call(owner, get("/api/v1/audit"), 403);
        call(ADMIN, get("/api/v1/audit"), 200);
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        assertThat(mvc.perform(get("/api/v1/sites")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
