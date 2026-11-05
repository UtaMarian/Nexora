package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.search.SearchIndexer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Reading without signing in, "this site wasn't shared with you", and links trimmed to what each person can open. */
@TestPropertySource(properties = "nexora.admin.backup-dir=${java.io.tmpdir}/nexora-test-backups")
class AnonymousAccessTest extends ApiTestSupport {
    @Autowired SearchIndexer indexer;

    private void farm(boolean enabled) throws Exception {
        call(ADMIN, jsonBody(put("/api/v1/admin/anonymous"), Map.of("enabled", enabled)), 204);
    }

    @BeforeEach
    void on() throws Exception {
        farm(true);
    }

    @AfterEach
    void off() throws Exception {
        farm(false);
    }

    /** A request with no credentials at all. */
    private MvcResult guest(MockHttpServletRequestBuilder b, int status) throws Exception {
        MvcResult r = mvc.perform(b).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getRequest().getRequestURI()).isEqualTo(status);
        return r;
    }

    private JsonNode guestJson(MockHttpServletRequestBuilder b) throws Exception {
        return json.readTree(guest(b, 200).getResponse().getContentAsString());
    }

    private void open(String login, UUID node) throws Exception {
        call(login, jsonBody(put("/api/v1/nodes/{id}/anonymous", node), Map.of("enabled", true)), 200);
    }

    private JsonNode node(String login, String path) throws Exception {
        return call(login, get("/api/v1/resolve").param("path", path), 200).get("node");
    }

    @Test
    void anOpenSiteCanBeReadWithoutSigningIn() throws Exception {
        String owner = createUser("owner");
        String url = "pub-" + suffix;
        UUID site = id(createSite(owner, url));
        UUID lib = defaultLibrary(owner, site);
        UUID file = id(upload(owner, lib, "Hello.txt", "hello", false, 201));

        guest(get("/api/v1/resolve").param("path", "/sites/" + url), 401);
        open(owner, site);

        assertThat(guestJson(get("/api/v1/auth/me")).get("anonymous").asBoolean()).isTrue();
        assertThat(guestJson(get("/api/v1/sites")).findValuesAsText("name")).contains(url);
        assertThat(guestJson(get("/api/v1/resolve").param("path", "/sites/" + url)).get("node").get("myRights")).hasSize(1);
        assertThat(guestJson(get("/api/v1/nodes/{id}/children", lib)).findValuesAsText("name")).contains("Hello.txt");
        assertThat(guest(get("/api/v1/nodes/{id}/content", file), 200).getResponse().getContentAsString()).isEqualTo("hello");

        // people's sign-in names stay private
        assertThat(guest(get("/api/v1/nodes/{id}", file), 200).getResponse().getContentAsString()).doesNotContain(owner);
    }

    @Test
    void visitorsCanOnlyRead() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "ro-" + suffix));
        UUID lib = defaultLibrary(owner, site);
        UUID file = id(upload(owner, lib, "Doc.txt", "one", false, 201));
        upload(owner, lib, "Doc.txt", "two", true, 201);
        open(owner, site);

        guest(jsonBody(post("/api/v1/nodes/{id}/folders", lib), Map.of("name", "X")).with(csrf()), 401);
        guest(delete("/api/v1/nodes/{id}", file).with(csrf()), 401);
        guest(jsonBody(put("/api/v1/sites/{id}/design", site), Map.of()).with(csrf()), 401);
        // nor the things around the content
        guest(get("/api/v1/nodes/{id}/permissions", site), 401);
        guest(get("/api/v1/sites/{id}/groups", site), 401);
        guest(get("/api/v1/nodes/{id}/versions", file), 401);
        guest(get("/api/v1/nodes/{id}/content", file).param("versionId", UUID.randomUUID().toString()), 401);
        guest(get("/api/v1/principals").param("q", "a"), 401);
        guest(get("/api/v1/recycle-bin"), 401);
        guest(get("/api/v1/audit"), 401);
        guest(get("/api/v1/admin/sites"), 401);
        guest(get("/api/v1/me/approvals"), 401);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf();
    }

    @Test
    void theFarmSwitchClosesEverythingAtOnceAndKeepsOwnersChoices() throws Exception {
        String owner = createUser("owner");
        String stranger = createUser("stranger");
        UUID site = id(createSite(owner, "kill-" + suffix));
        open(owner, site);
        call(stranger, get("/api/v1/nodes/{id}", site), 200);

        farm(false);
        guest(get("/api/v1/auth/me"), 401);
        guest(get("/api/v1/nodes/{id}", site), 401);
        call(stranger, get("/api/v1/nodes/{id}", site), 404); // signed in people lose it too
        call(owner, jsonBody(put("/api/v1/nodes/{id}/anonymous", id(createSite(owner, "kill2-" + suffix))), Map.of("enabled", true)), 409);

        farm(true);
        guest(get("/api/v1/nodes/{id}", site), 200);
    }

    @Test
    void onlyOwnersChooseAndOnlyAdministratorsFlipTheFarm() throws Exception {
        String owner = createUser("owner");
        String member = createUser("member");
        UUID site = id(createSite(owner, "who-" + suffix));
        addToGroup(siteGroup(site, "members"), member);
        call(member, jsonBody(put("/api/v1/nodes/{id}/anonymous", site), Map.of("enabled", true)), 403);
        call(owner, jsonBody(put("/api/v1/admin/anonymous"), Map.of("enabled", true)), 403);
        // a folder or file is not something this switch applies to
        UUID folder = id(call(owner, jsonBody(post("/api/v1/nodes/{id}/folders", defaultLibrary(owner, site)), Map.of("name", "F")), 201));
        call(owner, jsonBody(put("/api/v1/nodes/{id}/anonymous", folder), Map.of("enabled", true)), 400);
    }

    @Test
    void anonymousCanNeverBeGivenMoreThanRead() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "lvl-" + suffix));
        open(owner, site);
        JsonNode perms = call(owner, get("/api/v1/nodes/{id}/permissions", site), 200);
        String anon = null;
        for (JsonNode e : perms.get("entries")) if ("Anonymous visitors".equals(e.get("principal").get("displayName").asText())) anon = e.get("principal").get("id").asText();
        assertThat(anon).isNotNull();
        call(owner, jsonBody(put("/api/v1/nodes/{id}/permissions/{p}", site, anon), Map.of("level", "EDIT")), 400);
        call(owner, jsonBody(post("/api/v1/principals/{id}/members", siteGroup(site, "owners")), Map.of("principalId", anon)), 400);
        assertThat(call(ADMIN, get("/api/v1/principals").param("q", "Anonymous"), 200).findValuesAsText("displayName")).doesNotContain("Anonymous visitors");
    }

    @Test
    void oneLibraryCanBeOpenInAPrivateSite() throws Exception {
        String owner = createUser("owner");
        String url = "lib-" + suffix;
        UUID site = id(createSite(owner, url));
        UUID lib = defaultLibrary(owner, site);
        UUID other = id(call(owner, jsonBody(post("/api/v1/sites/{id}/libraries", site), Map.of("name", "Private")), 201));
        UUID secret = id(upload(owner, other, "Secret.txt", "s", false, 201));
        UUID file = id(upload(owner, lib, "Open.txt", "o", false, 201));
        open(owner, lib);

        guest(get("/api/v1/nodes/{id}/content", file), 200);
        guest(get("/api/v1/nodes/{id}/content", secret), 404);
        guest(get("/api/v1/nodes/{id}", other), 404);
        guest(get("/api/v1/resolve").param("path", "/sites/" + url), 401);

        JsonNode status = call(owner, get("/api/v1/nodes/{id}/anonymous", lib), 200);
        assertThat(status.get("enabled").asBoolean()).isTrue();
        call(owner, jsonBody(put("/api/v1/nodes/{id}/anonymous", lib), Map.of("enabled", false)), 200);
        guest(get("/api/v1/nodes/{id}/content", file), 404);
    }

    @Test
    void privateCornersOfAnOpenSiteStayPrivate() throws Exception {
        String owner = createUser("owner");
        String url = "corner-" + suffix;
        UUID site = id(createSite(owner, url));
        UUID lib = defaultLibrary(owner, site);
        UUID folder = id(call(owner, jsonBody(post("/api/v1/nodes/{id}/folders", lib), Map.of("name", "Board")), 201));
        call(owner, jsonBody(post("/api/v1/nodes/{id}/permissions/break-inheritance", folder), Map.of("copyParentEntries", false)), 200);
        UUID sub = id(call(owner, jsonBody(post("/api/v1/sites/{id}/subsites", site), Map.of("url", "team", "title", "Team")), 201));
        open(owner, site);

        assertThat(guestJson(get("/api/v1/nodes/{id}/children", lib)).findValuesAsText("name")).doesNotContain("Board");
        guest(get("/api/v1/nodes/{id}", folder), 404);
        guest(get("/api/v1/nodes/{id}", sub), 404);          // own groups, so its own choice
        guest(get("/api/v1/resolve").param("path", "/sites/" + url + "/team"), 401);

        // a subsite that shares its parent's people follows it
        UUID shared = id(call(owner, jsonBody(post("/api/v1/sites/{id}/subsites", site), Map.of("url", "open", "title", "Open", "uniquePermissions", false)), 201));
        guest(get("/api/v1/nodes/{id}", shared), 200);
        call(owner, jsonBody(put("/api/v1/nodes/{id}/anonymous", shared), Map.of("enabled", false)), 409);
    }

    @Test
    void draftsAndSearchFollowTheSameRules() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "srch-" + suffix));
        UUID hidden = id(createSite(owner, "srch-b" + suffix));
        String word = "zebrafinch" + suffix.replaceAll("\\d", "q");
        upload(owner, defaultLibrary(owner, site), word + "1.txt", "x", false, 201);
        upload(owner, defaultLibrary(owner, hidden), word + "2.txt", "x", false, 201);
        open(owner, site);
        indexer.drain();
        assertThat(guestJson(get("/api/v1/search").param("q", word)).get("total").asInt()).isEqualTo(1);

        JsonNode draft = call(owner, jsonBody(post("/api/v1/sites/{id}/pages", site), Map.of("title", "Draft", "kind", "PAGE")), 201);
        guest(get("/api/v1/pages/{id}", id(draft.get("page"))), 404);
        assertThat(guestJson(get("/api/v1/sites/{id}/pages", site))).isEmpty();
    }

    @Test
    void aRestoredCopyIsPrivate() throws Exception {
        String owner = createUser("owner");
        String url = "bak-" + suffix;
        UUID site = id(createSite(owner, url));
        open(owner, site);
        JsonNode started = call(ADMIN, post("/api/v1/admin/sites/{id}/backup", site), 202);
        JsonNode done = job(started);
        String copy = "bakcopy-" + suffix;
        job(call(ADMIN, jsonBody(post("/api/v1/admin/backups/{id}/restore", done.get("result").get("backupId").asText()),
                Map.of("sites", List.of(Map.of("name", url, "targetName", copy)), "directory", false)), 202));
        UUID restored = id(node(ADMIN, "/sites/" + copy));
        guest(get("/api/v1/nodes/{id}", restored), 404);
    }

    private JsonNode job(JsonNode started) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode j = call(ADMIN, get("/api/v1/admin/jobs/{id}", id(started)), 200);
            if (!List.of("QUEUED", "RUNNING").contains(j.get("status").asText())) {
                assertThat(j.get("status").asText()).as(j.toString()).isEqualTo("SUCCEEDED");
                return j;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job did not finish");
    }

    // ---- not shared

    @Test
    void aSignedInPersonIsToldTheSiteWasNotSharedWithoutLearningWhatIsInIt() throws Exception {
        String owner = createUser("owner");
        String stranger = createUser("stranger");
        String url = "ns-" + suffix;
        UUID site = id(createSite(owner, url));
        upload(owner, defaultLibrary(owner, site), "Real.txt", "x", false, 201);
        call(owner, jsonBody(post("/api/v1/sites/{id}/pages", site), Map.of("title", "Real page", "kind", "PAGE")), 201);
        String base = "/sites/" + url;

        JsonNode denied = call(stranger, get("/api/v1/resolve").param("path", base), 403);
        assertThat(denied.get("code").asText()).isEqualTo("site_not_shared");
        // the same answer for what exists and what does not
        assertThat(call(stranger, get("/api/v1/resolve").param("path", base + "/Shared Documents/Real.txt"), 403).get("code").asText()).isEqualTo("site_not_shared");
        assertThat(call(stranger, get("/api/v1/resolve").param("path", base + "/Shared Documents/Nope.txt"), 403).get("code").asText()).isEqualTo("site_not_shared");
        assertThat(call(stranger, get("/api/v1/pages/resolve").param("path", base + "/SitePages/real-page.aspx"), 403).get("code").asText()).isEqualTo("site_not_shared");
        assertThat(call(stranger, get("/api/v1/pages/resolve").param("path", base + "/SitePages/nope.aspx"), 403).get("code").asText()).isEqualTo("site_not_shared");

        // really nothing there, and missing things in a site you can open, stay not found
        call(stranger, get("/api/v1/resolve").param("path", "/sites/no-such-site-" + suffix), 404);
        call(owner, get("/api/v1/resolve").param("path", base + "/Shared Documents/Nope.txt"), 404);
    }

    @Test
    void aFileSharedWithSomeoneStillOpensInASiteTheyCannotSee() throws Exception {
        String owner = createUser("owner");
        String friend = createUser("friend");
        String url = "sh-" + suffix;
        UUID site = id(createSite(owner, url));
        UUID file = id(upload(owner, defaultLibrary(owner, site), "For you.txt", "x", false, 201));
        UUID friendId = UUID.fromString(call(friend, get("/api/v1/auth/me"), 200).get("id").asText());
        call(owner, jsonBody(post("/api/v1/nodes/{id}/share-people", file), Map.of("principals", List.of(friendId), "level", "READ")), 204);
        assertThat(node(friend, "/sites/" + url + "/Shared Documents/For you.txt").get("id").asText()).isEqualTo(file.toString());
    }

    // ---- trimmed links

    private Map<String, Object> link(String label, String url) {
        return Map.of("label", label, "url", url);
    }

    private void navigation(String owner, UUID site, List<Map<String, Object>> nav) throws Exception {
        Map<String, Object> body = new HashMap<>(json.convertValue(call(owner, get("/api/v1/sites/{id}/design", site).param("edit", "true"), 200).get("design"), Map.class));
        body.put("navigation", nav);
        call(owner, jsonBody(put("/api/v1/sites/{id}/design", site), body), 200);
    }

    @Test
    void menusHideWhatTheReaderCannotOpen() throws Exception {
        String owner = createUser("owner");
        String reader = createUser("reader");
        String url = "nav-" + suffix, secretUrl = "navsecret-" + suffix;
        UUID site = id(createSite(owner, url));
        createSite(owner, secretUrl);
        addToGroup(siteGroup(site, "visitors"), reader);
        String priv = "/sites/" + secretUrl + "/Shared Documents";

        navigation(owner, site, List.of(link("Mine", "/sites/" + url), link("Secret", priv), link("Outside", "https://example.com/x"),
                Map.of("label", "Menu", "groups", List.of(
                        Map.of("heading", "Open", "links", List.of(link("Home", "/sites/" + url), link("Hidden", priv))),
                        Map.of("heading", "Gone", "links", List.of(link("Hidden2", priv))))),
                Map.of("label", "All secret", "groups", List.of(Map.of("heading", "x", "links", List.of(link("Hidden3", priv)))))));

        String seen = call(reader, get("/api/v1/sites/{id}/design", site), 200).get("design").get("navigation").toString();
        assertThat(seen).contains("Mine", "Outside", "Menu", "Open").doesNotContain("Secret", "Hidden", "Gone", "All secret", secretUrl);

        // the owner's editor keeps everything, or saving would quietly delete the links
        String raw = call(owner, get("/api/v1/sites/{id}/design", site).param("edit", "true"), 200).get("design").get("navigation").toString();
        assertThat(raw).contains("Secret", "Hidden3");
        call(reader, get("/api/v1/sites/{id}/design", site).param("edit", "true"), 403);
    }

    @Test
    void aHubHidesItselfFromPeopleWhoCannotOpenIt() throws Exception {
        String owner = createUser("owner");
        String reader = createUser("reader");
        UUID hub = id(createSite(owner, "hubsecret-" + suffix));
        UUID member = id(createSite(owner, "hubmember-" + suffix));
        addToGroup(siteGroup(member, "visitors"), reader);
        call(ADMIN, jsonBody(put("/api/v1/admin/sites/{id}/hub", hub), Map.of("enabled", true)), 204);
        call(owner, jsonBody(put("/api/v1/sites/{id}/hub", hub), Map.of("title", "Secret hub", "navigation", List.of(link("Inside", "/sites/hubmember-" + suffix)))), 200);
        Map<String, Object> joining = new HashMap<>();
        joining.put("hubId", hub);
        call(owner, jsonBody(put("/api/v1/sites/{id}/hub-association", member), joining), 204);

        JsonNode seen = call(reader, get("/api/v1/sites/{id}/design", member), 200);
        assertThat(seen.toString()).doesNotContain("Secret hub", "Inside", hub.toString());
        assertThat(call(owner, get("/api/v1/sites/{id}/design", member), 200).get("hub").get("title").asText()).isEqualTo("Secret hub");

        addToGroup(siteGroup(hub, "visitors"), reader);
        assertThat(call(reader, get("/api/v1/sites/{id}/design", member), 200).get("hub").get("title").asText()).isEqualTo("Secret hub");
    }

    @Test
    void aSubsiteDoesNotNameAParentTheReaderCannotOpen() throws Exception {
        String owner = createUser("owner");
        String reader = createUser("reader");
        UUID parent = id(createSite(owner, "hiddenparent-" + suffix));
        UUID sub = id(call(owner, jsonBody(post("/api/v1/sites/{id}/subsites", parent), Map.of("url", "team", "title", "Team")), 201));
        addToGroup(siteGroup(sub, "visitors"), reader);
        assertThat(call(reader, get("/api/v1/sites/{id}/design", sub), 200).get("parent")).isNull();
    }

    @Test
    void quickLinksAreTrimmedForReadersButNotForEditors() throws Exception {
        String owner = createUser("owner");
        String reader = createUser("reader");
        String url = "ql-" + suffix, secretUrl = "qlsecret-" + suffix;
        UUID site = id(createSite(owner, url));
        createSite(owner, secretUrl);
        addToGroup(siteGroup(site, "visitors"), reader);
        JsonNode page = call(owner, jsonBody(post("/api/v1/sites/{id}/pages", site), Map.of("title", "P", "kind", "PAGE")), 201);
        UUID pid = id(page.get("page"));
        Map<String, Object> part = Map.of("id", "q1", "type", "quicklinks", "props", Map.of("title", "Go", "links",
                List.of(link("Fine", "/sites/" + url), link("Hidden", "/sites/" + secretUrl))));
        call(owner, jsonBody(put("/api/v1/pages/{id}/draft", pid), Map.of("title", "P", "revision", page.get("revision").asInt(),
                "canvas", Map.of("sections", List.of(Map.of("layout", "one", "columns", List.of(List.of(part))))))), 200);
        call(owner, post("/api/v1/pages/{id}/publish", pid), 200);

        assertThat(call(reader, get("/api/v1/pages/{id}", pid), 200).get("published").toString()).contains("Fine").doesNotContain("Hidden");
        assertThat(call(owner, get("/api/v1/pages/{id}", pid), 200).get("published").toString()).contains("Hidden");
    }
}
