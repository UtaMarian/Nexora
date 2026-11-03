package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.office.WopiTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** The WOPI host as the Office editor sees it: server-to-server calls with an access token, no session. */
class WopiTest extends ApiTestSupport {
    @Autowired WopiTokenService tokens;

    record Fixture(String owner, String member, String visitor, UUID lib, UUID file) {}

    private Fixture fixture() throws Exception {
        String owner = createUser("owner");
        String member = createUser("member");
        String visitor = createUser("visitor");
        UUID site = id(createSite(owner, "office-" + suffix));
        addToGroup(siteGroup(site, "members"), member);
        addToGroup(siteGroup(site, "visitors"), visitor);
        UUID lib = defaultLibrary(owner, site);
        UUID file = id(upload(owner, lib, "Budget.xlsx", "v1-bytes", false, 201));
        return new Fixture(owner, member, visitor, lib, file);
    }

    private String tokenFor(String login, UUID file) throws Exception {
        return tokens.issue(principalId(login), file, "http://localhost:8088");
    }

    private MvcResult wopi(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn();
    }

    private JsonNode info(String token, UUID file) throws Exception {
        MvcResult r = wopi(get("/wopi/files/{id}", file).param("access_token", token));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private MvcResult put(String token, UUID file, String content, String timestamp) throws Exception {
        MockHttpServletRequestBuilder b = post("/wopi/files/{id}/contents", file).param("access_token", token)
                .content(content.getBytes(StandardCharsets.UTF_8));
        if (timestamp != null) b = b.header("X-COOL-WOPI-Timestamp", timestamp);
        return wopi(b);
    }

    @Test
    void editorLoadsAndSavesAsNewVersions() throws Exception {
        Fixture f = fixture();
        String token = tokenFor(f.member(), f.file());

        JsonNode info = info(token, f.file());
        assertThat(info.get("BaseFileName").asText()).isEqualTo("Budget.xlsx");
        assertThat(info.get("Size").asLong()).isEqualTo(8);
        assertThat(info.get("UserCanWrite").asBoolean()).isTrue();
        assertThat(info.get("UserFriendlyName").asText()).isEqualTo("member");
        assertThat(info.get("PostMessageOrigin").asText()).isEqualTo("http://localhost:8088");

        MvcResult content = wopi(get("/wopi/files/{id}/contents", f.file()).param("access_token", token));
        assertThat(content.getResponse().getContentAsString()).isEqualTo("v1-bytes");

        MvcResult saved = put(token, f.file(), "v2-bytes", info.get("LastModifiedTime").asText());
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        String newTimestamp = json.readTree(saved.getResponse().getContentAsString()).get("LastModifiedTime").asText();
        assertThat(newTimestamp).isNotEqualTo(info.get("LastModifiedTime").asText());

        // the editor keeps saving with the timestamp we handed back
        assertThat(put(token, f.file(), "v3-bytes", newTimestamp).getResponse().getStatus()).isEqualTo(200);

        JsonNode history = call(f.owner(), get("/api/v1/nodes/{id}/versions", f.file()), 200);
        assertThat(history).extracting(v -> v.get("label").asText()).containsExactly("3.0", "2.0", "1.0");
        assertThat(history.get(0).get("createdBy").get("login").asText()).isEqualTo(f.member());
        assertThat(history.get(0).get("comment").asText()).contains("browser editor");
        assertThat(download(f.owner(), f.file())).isEqualTo("v3-bytes");
    }

    @Test
    void changesOutsideTheEditorAreDetectedAsConflicts() throws Exception {
        Fixture f = fixture();
        String token = tokenFor(f.member(), f.file());
        String loadedAt = info(token, f.file()).get("LastModifiedTime").asText();

        upload(f.owner(), f.lib(), "Budget.xlsx", "uploaded meanwhile", true, 201);

        MvcResult r = put(token, f.file(), "stale edit", loadedAt);
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(r.getResponse().getContentAsString()).contains("1010");
        assertThat(download(f.owner(), f.file())).isEqualTo("uploaded meanwhile");
    }

    @Test
    void readersGetAReadOnlyEditorAndCannotSave() throws Exception {
        Fixture f = fixture();
        String token = tokenFor(f.visitor(), f.file());
        assertThat(info(token, f.file()).get("UserCanWrite").asBoolean()).isFalse();
        assertThat(put(token, f.file(), "hack", null).getResponse().getStatus()).isEqualTo(401);
        assertThat(download(f.owner(), f.file())).isEqualTo("v1-bytes");
    }

    @Test
    void checkOutByAnotherUserMakesTheEditorReadOnly() throws Exception {
        Fixture f = fixture();
        call(f.owner(), post("/api/v1/nodes/{id}/checkout", f.file()), 200);

        String memberToken = tokenFor(f.member(), f.file());
        assertThat(info(memberToken, f.file()).get("UserCanWrite").asBoolean()).isFalse();
        assertThat(put(memberToken, f.file(), "blocked", null).getResponse().getStatus()).isEqualTo(401);

        // the person who checked it out edits their private draft
        String ownerToken = tokenFor(f.owner(), f.file());
        assertThat(info(ownerToken, f.file()).get("UserCanWrite").asBoolean()).isTrue();
        assertThat(put(ownerToken, f.file(), "draft", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(download(f.member(), f.file())).isEqualTo("v1-bytes");
        call(f.owner(), post("/api/v1/nodes/{id}/checkin", f.file()), 200);
        assertThat(download(f.member(), f.file())).isEqualTo("draft");
    }

    @Test
    void revokedAccessAppliesToOpenEditors() throws Exception {
        Fixture f = fixture();
        String token = tokenFor(f.member(), f.file());
        call(f.owner(), post("/api/v1/nodes/{id}/permissions/break-inheritance", f.file()), 200);
        call(f.owner(), delete("/api/v1/nodes/{id}/permissions/{p}", f.file(), siteGroupOf(f, "members")), 200);

        assertThat(wopi(get("/wopi/files/{id}", f.file()).param("access_token", token)).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void badTokensAreRejected() throws Exception {
        Fixture f = fixture();
        String token = tokenFor(f.member(), f.file());
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertThat(wopi(get("/wopi/files/{id}", f.file()).param("access_token", tampered)).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(wopi(get("/wopi/files/{id}", f.file())).getResponse().getStatus()).isEqualTo(401);

        // a token for one file does not open another
        UUID other = id(upload(f.owner(), f.lib(), "Other.docx", "x", false, 201));
        assertThat(wopi(get("/wopi/files/{id}", other).param("access_token", token)).getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void sessionEndpointReportsAnUnreachableEditor() throws Exception {
        Fixture f = fixture();
        JsonNode err = call(f.member(), jsonBody(post("/api/v1/nodes/{id}/office-session", f.file()),
                Map.of("origin", "http://localhost:8088")), 503);
        assertThat(err.get("code").asText()).isEqualTo("office_unavailable");
        JsonNode caps = call(f.member(), get("/api/v1/office"), 200);
        assertThat(caps.get("available").asBoolean()).isFalse();
    }

    private UUID siteGroupOf(Fixture f, String role) throws Exception {
        JsonNode node = call(ADMIN, get("/api/v1/nodes/{id}", f.file()), 200);
        return siteGroup(UUID.fromString(node.get("node").get("siteId").asText()), role);
    }
}
