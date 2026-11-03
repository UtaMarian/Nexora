package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Drives the real HTTP API (security, validation, JSON) against in-memory H2 and a temp blob store. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTestSupport {
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;

    protected static final String ADMIN = "admin";
    protected static final String ADMIN_PW = "admin-test-pw";
    protected static final String PW = "password-123";

    protected String suffix;

    @BeforeEach
    void uniqueSuffix() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    /** A request builder authenticated as {@code login} via HTTP Basic. */
    protected MockHttpServletRequestBuilder as(String login, MockHttpServletRequestBuilder b) {
        return b.with(httpBasic(login, login.equals(ADMIN) ? ADMIN_PW : PW));
    }

    protected JsonNode call(String login, MockHttpServletRequestBuilder b, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(as(login, b)).andReturn();
        int status = r.getResponse().getStatus();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (status != expectedStatus) {
            throw new AssertionError("Expected HTTP " + expectedStatus + " but got " + status + ": " + body);
        }
        return body.isEmpty() ? null : json.readTree(body);
    }

    protected MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder b, Object body) throws Exception {
        return b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
    }

    protected String createUser(String base) throws Exception {
        String login = base + "-" + suffix;
        call(ADMIN, jsonBody(post("/api/v1/admin/users"),
                Map.of("login", login, "displayName", base, "password", PW)), 201);
        return login;
    }

    protected UUID principalId(String login) throws Exception {
        JsonNode list = call(ADMIN, get("/api/v1/principals").param("q", login), 200);
        for (JsonNode p : list) if (p.get("login").asText().equalsIgnoreCase(login)) return id(p);
        throw new AssertionError("No principal " + login);
    }

    protected JsonNode createSite(String owner, String url) throws Exception {
        return call(owner, jsonBody(post("/api/v1/sites"), Map.of("url", url, "title", "Site " + url)), 201);
    }

    protected UUID defaultLibrary(String login, UUID siteId) throws Exception {
        JsonNode libs = call(login, get("/api/v1/sites/{id}/libraries", siteId), 200);
        return id(libs.get(0));
    }

    protected UUID siteGroup(UUID siteId, String suffixName) throws Exception {
        JsonNode groups = call(ADMIN, get("/api/v1/sites/{id}/groups", siteId), 200);
        for (JsonNode g : groups) if (g.get("login").asText().endsWith("-" + suffixName)) return id(g);
        throw new AssertionError("No group " + suffixName);
    }

    protected void addToGroup(UUID groupId, String login) throws Exception {
        call(ADMIN, jsonBody(post("/api/v1/principals/{id}/members", groupId),
                Map.of("principalId", principalId(login))), 204);
    }

    protected JsonNode upload(String login, UUID parentId, String name, String content, boolean overwrite,
                              int expectedStatus) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
        JsonNode r = call(login, multipart("/api/v1/nodes/{id}/files", parentId).file(file)
                .param("overwrite", String.valueOf(overwrite)), expectedStatus);
        return r != null && r.isArray() ? r.get(0) : r;
    }

    protected String download(String login, UUID fileId) throws Exception {
        MvcResult r = mvc.perform(as(login, get("/api/v1/nodes/{id}/content", fileId))).andReturn();
        if (r.getResponse().getStatus() != 200) {
            throw new AssertionError("Download failed: " + r.getResponse().getStatus() + " "
                    + r.getResponse().getContentAsString());
        }
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    protected static UUID id(JsonNode n) {
        return UUID.fromString(n.get("id").asText());
    }
}
