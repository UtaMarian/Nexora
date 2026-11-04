package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.search.SearchIndexer;
import io.nexora.workflow.WorkflowEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Subsites, hub sites, mega menus, the new web parts and page templates. */
@TestPropertySource(properties = "nexora.admin.backup-dir=${java.io.tmpdir}/nexora-test-backups")
class IntranetTest extends ApiTestSupport {
    @Autowired SearchIndexer indexer;
    @Autowired WorkflowEngine engine;

    private JsonNode subsite(String login, UUID parent, String url, Boolean unique, int status) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("url", url, "title", "Sub " + url));
        if (unique != null) body.put("uniquePermissions", unique);
        return call(login, jsonBody(post("/api/v1/sites/{id}/subsites", parent), body), status);
    }

    private JsonNode node(String login, String path) throws Exception {
        return call(login, get("/api/v1/resolve").param("path", path), 200);
    }

    // ---- subsites

    @Test
    void aSubsiteIsARealSiteInsideItsParent() throws Exception {
        String owner = createUser("owner");
        String visitor = createUser("visitor");
        String url = "parent-" + suffix;
        UUID parent = id(createSite(owner, url));
        addToGroup(siteGroup(parent, "visitors"), visitor);

        JsonNode sub = subsite(owner, parent, "payroll", null, 201);
        UUID subId = id(sub);
        String path = "/sites/" + url + "/payroll";
        assertThat(sub.get("path").asText()).isEqualTo(path);
        assertThat(sub.get("siteId").asText()).isEqualTo(subId.toString());
        assertThat(call(owner, get("/api/v1/sites/{id}/subsites", parent), 200).findValuesAsText("name")).containsExactly("payroll");

        // its own library, files, breadcrumbs and pages all work at the deeper address
        UUID lib = defaultLibrary(owner, subId);
        UUID file = id(upload(owner, lib, "Doc.txt", "x", false, 201));
        JsonNode resolved = node(owner, path + "/Shared Documents/Doc.txt");
        assertThat(resolved.get("node").get("id").asText()).isEqualTo(file.toString());
        assertThat(resolved.get("breadcrumbs").findValuesAsText("id")).first().isEqualTo(subId.toString());
        assertThat(call(owner, get("/api/v1/nodes/{id}/versions", file), 200)).hasSize(1); // needs the library
        call(owner, post("/api/v1/nodes/{id}/checkout", file), 200);
        call(owner, post("/api/v1/nodes/{id}/discard-checkout", file), 200);

        JsonNode page = call(owner, jsonBody(post("/api/v1/sites/{id}/pages", subId), Map.of("title", "Welcome", "kind", "PAGE")), 201);
        assertThat(page.get("page").get("url").asText()).isEqualTo(path + "/SitePages/welcome.aspx");
        assertThat(call(owner, get("/api/v1/pages/resolve").param("path", path + "/SitePages/welcome.aspx"), 200).get("page").get("id")).isEqualTo(page.get("page").get("id"));

        // unique permissions: the parent's visitors do not get in, and groups do not clash with another parent's subsite
        call(visitor, get("/api/v1/nodes/{id}", subId), 404);
        UUID other = id(createSite(owner, "other-" + suffix));
        assertThat(subsite(owner, other, "payroll", null, 201).get("path").asText()).endsWith("/payroll");
    }

    @Test
    void aSubsiteCanShareItsParentsPermissions() throws Exception {
        String owner = createUser("owner");
        String visitor = createUser("visitor");
        UUID parent = id(createSite(owner, "share-" + suffix));
        addToGroup(siteGroup(parent, "visitors"), visitor);
        UUID sub = id(subsite(owner, parent, "open", false, 201));
        assertThat(call(visitor, get("/api/v1/nodes/{id}", sub), 200).get("node").get("name").asText()).isEqualTo("open");
        assertThat(call(owner, get("/api/v1/sites/{id}/groups", sub), 200)).isEmpty();
    }

    @Test
    void creatingSubsitesHasRules() throws Exception {
        String owner = createUser("owner");
        String member = createUser("member");
        UUID parent = id(createSite(owner, "rules-" + suffix));
        addToGroup(siteGroup(parent, "members"), member);
        subsite(member, parent, "nope", null, 403);
        subsite(owner, parent, "Bad Name", null, 400);
        subsite(owner, parent, "shared-documents", null, 201);
        subsite(owner, parent, "shared-documents", null, 409);

        UUID a = id(subsite(owner, parent, "a", null, 201));
        UUID b = id(subsite(owner, a, "b", null, 201));
        UUID c = id(subsite(owner, b, "c", null, 201));
        subsite(owner, c, "too-deep", null, 400);
    }

    @Test
    void subsitesGoAwayAndComeBackWithTheirParent() throws Exception {
        String owner = createUser("owner");
        String url = "gone-" + suffix;
        UUID parent = id(createSite(owner, url));
        UUID sub = id(subsite(owner, parent, "team", null, 201));
        UUID file = id(upload(owner, defaultLibrary(owner, sub), "Deep.txt", "x", false, 201));
        String w = "deepword" + suffix.replaceAll("\\d", "x");
        upload(owner, defaultLibrary(owner, sub), w + ".txt", "x", false, 201);
        indexer.drain();
        assertThat(call(owner, get("/api/v1/search").param("q", w), 200).get("total").asInt()).isEqualTo(1);

        call(ADMIN, delete("/api/v1/nodes/{id}", parent), 204);
        call(owner, get("/api/v1/nodes/{id}", file), 404);
        indexer.drain();
        assertThat(call(ADMIN, get("/api/v1/search").param("q", w), 200).get("total").asInt()).isZero();

        call(ADMIN, post("/api/v1/recycle-bin/{id}/restore", parent), 200);
        assertThat(download(owner, file)).isEqualTo("x");
        indexer.drain();
        assertThat(call(owner, get("/api/v1/search").param("q", w), 200).get("total").asInt()).isEqualTo(1);

        // purging takes the subsite, its groups and its URL
        call(ADMIN, delete("/api/v1/nodes/{id}", parent), 204);
        call(ADMIN, delete("/api/v1/recycle-bin/{id}", parent), 204);
        assertThat(call(ADMIN, get("/api/v1/principals").param("q", url), 200)).isEmpty();
        createSite(owner, url);
    }

    @Test
    void theParentsOwnerLooksAfterSubsitesInTheRecycleBin() throws Exception {
        String owner = createUser("owner");
        String member = createUser("member");
        UUID parent = id(createSite(owner, "bin-" + suffix));
        addToGroup(siteGroup(parent, "members"), member);
        UUID sub = id(subsite(owner, parent, "team", null, 201));
        call(member, delete("/api/v1/nodes/{id}", sub), 404); // has no access to it at all
        call(owner, delete("/api/v1/nodes/{id}", sub), 204);
        assertThat(call(owner, get("/api/v1/recycle-bin").param("siteId", parent.toString()), 200).findValuesAsText("name")).contains("team");
        call(owner, post("/api/v1/recycle-bin/{id}/restore", sub), 200);
        call(owner, get("/api/v1/nodes/{id}", sub), 200);
    }

    @Test
    void aRuleInASubsiteFindsItsOwnList() throws Exception {
        String owner = createUser("owner");
        UUID parent = id(createSite(owner, "wf-" + suffix));
        UUID sub = id(subsite(owner, parent, "ops", null, 201));
        UUID lib = defaultLibrary(owner, sub);
        UUID log = id(call(owner, jsonBody(post("/api/v1/sites/{id}/lists", sub), Map.of("name", "Log", "template", "CUSTOM")), 201));
        call(owner, jsonBody(post("/api/v1/nodes/{id}/rules", lib), Map.of("name", "Log it", "definition", Map.of("trigger", "CREATED", "conditions", List.of(),
                "actions", List.of(Map.of("type", "CREATE_ITEM", "list", "Log", "title", "Added {title}"))))), 201);
        upload(owner, lib, "Doc.txt", "x", false, 201);
        engine.drain();
        assertThat(call(owner, get("/api/v1/nodes/{id}/children", log), 200).findValuesAsText("title")).containsExactly("Added Doc.txt");
    }

    private JsonNode job(JsonNode started) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode j = call(ADMIN, get("/api/v1/admin/jobs/{id}", id(started)), 200);
            if (!List.of("QUEUED", "RUNNING").contains(j.get("status").asText())) return j;
            Thread.sleep(100);
        }
        throw new AssertionError("Job did not finish");
    }

    @Test
    void aBackupCarriesSubsitesAndRestoresThemUnderTheNewAddress() throws Exception {
        String owner = createUser("owner");
        String url = "pack-" + suffix;
        UUID parent = id(createSite(owner, url));
        upload(owner, defaultLibrary(owner, parent), "Top.txt", "top", false, 201);
        UUID sub = id(subsite(owner, parent, "team", null, 201));
        upload(owner, defaultLibrary(owner, sub), "Sub.txt", "in the subsite", false, 201);
        UUID deep = id(subsite(owner, sub, "deep", false, 201));
        upload(owner, defaultLibrary(owner, deep), "Deep.txt", "two levels down", false, 201);
        call(owner, jsonBody(post("/api/v1/sites/{id}/pages", sub), Map.of("title", "Team page", "kind", "PAGE")), 201);

        JsonNode done = job(call(ADMIN, post("/api/v1/admin/sites/{id}/backup", parent), 202));
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        String backup = done.get("result").get("backupId").asText();

        String copy = "copy-" + suffix;
        JsonNode restored = job(call(ADMIN, jsonBody(post("/api/v1/admin/backups/{id}/restore", backup),
                Map.of("sites", List.of(Map.of("name", url, "targetName", copy)), "directory", false)), 202));
        assertThat(restored.get("status").asText()).as(restored.toString()).isEqualTo("SUCCEEDED");

        String base = "/sites/" + copy;
        assertThat(download(ADMIN, id(node(ADMIN, base + "/Shared Documents/Top.txt").get("node")))).isEqualTo("top");
        assertThat(download(ADMIN, id(node(ADMIN, base + "/team/Shared Documents/Sub.txt").get("node")))).isEqualTo("in the subsite");
        assertThat(download(ADMIN, id(node(ADMIN, base + "/team/deep/Shared Documents/Deep.txt").get("node")))).isEqualTo("two levels down");
        JsonNode teamSite = node(ADMIN, base + "/team").get("node");
        assertThat(teamSite.get("kind").asText()).isEqualTo("SITE");
        assertThat(node(ADMIN, base + "/team/deep").get("node").get("inheritsPermissions").asBoolean()).isTrue();
        assertThat(call(ADMIN, get("/api/v1/sites/{id}/pages", id(teamSite)), 200).findValuesAsText("title")).contains("Team page");
        // the top-level package holds the subsite once, not as a site of its own as well
        assertThat(call(ADMIN, get("/api/v1/nodes/{id}/children", id(node(ADMIN, base).get("node"))), 200).findValuesAsText("name")).containsOnlyOnce("team");
    }

    // ---- hubs

    private void link(String login, UUID site, UUID hub) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("hubId", hub);
        call(login, jsonBody(put("/api/v1/sites/{id}/hub-association", site), body), 204);
    }

    private JsonNode design(String login, UUID site) throws Exception {
        return call(login, get("/api/v1/sites/{id}/design", site), 200);
    }

    @Test
    void aHubSharesItsBarAndThemeWithItsSites() throws Exception {
        String owner = createUser("owner");
        UUID hub = id(createSite(owner, "hub-" + suffix));
        UUID member = id(createSite(owner, "member-" + suffix));
        UUID sub = id(subsite(owner, member, "team", null, 201));

        call(owner, jsonBody(put("/api/v1/admin/sites/{id}/hub", hub), Map.of("enabled", true)), 403);
        call(ADMIN, jsonBody(put("/api/v1/admin/sites/{id}/hub", hub), Map.of("enabled", true)), 204);
        assertThat(design(owner, hub).get("isHub").asBoolean()).isTrue();

        Map<String, Object> menu = Map.of("label", "Departments", "groups", List.of(
                Map.of("heading", "Business", "links", List.of(Map.of("label", "Finance", "url", "/sites/finance"))),
                Map.of("heading", "Field", "links", List.of(Map.of("label", "Safety", "url", "https://example.com/safety")))));
        call(owner, jsonBody(put("/api/v1/sites/{id}/hub", hub), Map.of("title", "The Hub", "navigation", List.of(Map.of("label", "Home", "url", "/sites/x"), menu))), 200);

        link(owner, member, hub);
        JsonNode seen = design(owner, member).get("hub");
        assertThat(seen.get("title").asText()).isEqualTo("The Hub");
        assertThat(seen.get("navigation").get(1).get("groups").get(0).get("links").get(0).get("label").asText()).isEqualTo("Finance");
        assertThat(design(owner, sub).get("hub").get("id").asText()).isEqualTo(hub.toString()); // a subsite follows its parent

        link(owner, member, null);
        assertThat(design(owner, member).get("hub")).isNull();
        assertThat(design(owner, sub).get("hub")).isNull();
    }

    @Test
    void hubRulesHold() throws Exception {
        String owner = createUser("owner");
        String stranger = createUser("stranger");
        UUID hub = id(createSite(owner, "hub-" + suffix));
        UUID other = id(createSite(owner, "plain-" + suffix));
        UUID mine = id(createSite(stranger, "mine-" + suffix));

        call(owner, jsonBody(put("/api/v1/sites/{id}/hub-association", mine), Map.of("hubId", hub)), 404); // not their site
        call(stranger, jsonBody(put("/api/v1/sites/{id}/hub-association", mine), Map.of("hubId", hub)), 404); // cannot see the hub
        call(owner, jsonBody(put("/api/v1/sites/{id}/hub-association", other), Map.of("hubId", other)), 400); // not a hub
        call(owner, jsonBody(put("/api/v1/sites/{id}/hub", hub), Map.of("title", "Hub", "navigation", List.of())), 400); // not registered

        call(ADMIN, jsonBody(put("/api/v1/admin/sites/{id}/hub", hub), Map.of("enabled", true)), 204);
        Map<String, Object> bad = Map.of("title", "Hub", "navigation", List.of(Map.of("label", "Bad", "url", "javascript:alert(1)")));
        call(owner, jsonBody(put("/api/v1/sites/{id}/hub", hub), bad), 400);
        Map<String, Object> badChild = Map.of("title", "Hub", "navigation", List.of(Map.of("label", "Menu", "groups",
                List.of(Map.of("heading", "x", "links", List.of(Map.of("label", "Bad", "url", "javascript:alert(1)")))))));
        call(owner, jsonBody(put("/api/v1/sites/{id}/hub", hub), badChild), 400);
        call(owner, jsonBody(put("/api/v1/sites/{id}/hub-association", hub), Map.of("hubId", hub)), 400);

        // subsites are not hubs, and follow their parent rather than joining one of their own
        UUID sub = id(subsite(owner, other, "team", null, 201));
        call(ADMIN, jsonBody(put("/api/v1/admin/sites/{id}/hub", sub), Map.of("enabled", true)), 400);
        call(owner, jsonBody(put("/api/v1/sites/{id}/hub-association", sub), Map.of("hubId", hub)), 400);
    }

    private UUID publishNews(String login, UUID site, String title) throws Exception {
        JsonNode p = call(login, jsonBody(post("/api/v1/sites/{id}/pages", site), Map.of("title", title, "kind", "NEWS")), 201);
        call(login, post("/api/v1/pages/{id}/publish", id(p.get("page"))), 200);
        return id(p.get("page"));
    }

    @Test
    void hubNewsRollsUpOnlyWhatYouCanSee() throws Exception {
        String owner = createUser("owner");
        String reader = createUser("reader");
        UUID hub = id(createSite(owner, "hub-" + suffix));
        UUID open = id(createSite(owner, "open-" + suffix));
        UUID closed = id(createSite(owner, "closed-" + suffix));
        call(ADMIN, jsonBody(put("/api/v1/admin/sites/{id}/hub", hub), Map.of("enabled", true)), 204);
        link(owner, open, hub);
        link(owner, closed, hub);
        addToGroup(siteGroup(hub, "visitors"), reader);
        addToGroup(siteGroup(open, "visitors"), reader);
        publishNews(owner, hub, "From the hub");
        publishNews(owner, open, "From the open site");
        publishNews(owner, closed, "Secret news");

        assertThat(call(owner, get("/api/v1/sites/{id}/news", hub).param("scope", "hub"), 200).findValuesAsText("title"))
                .containsExactlyInAnyOrder("From the hub", "From the open site", "Secret news");
        JsonNode trimmed = call(reader, get("/api/v1/sites/{id}/news", hub).param("scope", "hub"), 200);
        assertThat(trimmed.findValuesAsText("title")).containsExactlyInAnyOrder("From the hub", "From the open site");
        assertThat(trimmed.findValuesAsText("siteTitle")).contains("Site open-" + suffix);
        assertThat(call(reader, get("/api/v1/sites/{id}/news", hub), 200).findValuesAsText("title")).containsExactly("From the hub");
    }

    // ---- mega menus

    @Test
    void siteNavigationCanOpenAMegaMenu() throws Exception {
        String owner = createUser("owner");
        String url = "nav-" + suffix;
        UUID site = id(createSite(owner, url));
        JsonNode d = design(owner, site).get("design");
        Map<String, Object> body = json.convertValue(d, Map.class);
        body.put("navigation", List.of(Map.of("label", "Teams", "groups", List.of(Map.of("heading", "North", "links",
                List.of(Map.of("label", "Oslo", "url", "/sites/" + url + "/oslo")))))));
        JsonNode saved = call(owner, jsonBody(put("/api/v1/sites/{id}/design", site), body), 200).get("design");
        assertThat(saved.get("navigation").get(0).get("groups").get(0).get("heading").asText()).isEqualTo("North");
        assertThat(saved.get("navigation").get(0).get("url")).isNull();

        body.put("navigation", List.of(Map.of("label", "Plain, no address")));
        call(owner, jsonBody(put("/api/v1/sites/{id}/design", site), body), 400);
    }

    // ---- web parts

    private JsonNode saveCanvas(String login, UUID site, Map<String, Object> part, int status) throws Exception {
        JsonNode page = call(login, jsonBody(post("/api/v1/sites/{id}/pages", site), Map.of("title", "P " + UUID.randomUUID(), "kind", "PAGE")), 201);
        Map<String, Object> canvas = Map.of("sections", List.of(Map.of("layout", "one", "columns", List.of(List.of(part)))));
        return call(login, jsonBody(put("/api/v1/pages/{id}/draft", id(page.get("page"))),
                Map.of("title", page.get("page").get("title").asText(), "revision", page.get("revision").asInt(), "canvas", canvas)), status);
    }

    private static Map<String, Object> part(String type, Map<String, Object> props) {
        return Map.of("id", "p1", "type", type, "props", props);
    }

    private JsonNode firstPart(JsonNode saved) {
        return saved.get("draft").get("canvas").get("sections").get(0).get("columns").get(0).get(0).get("props");
    }

    @Test
    void newWebPartsSaveAndRefuseBadInput() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "parts-" + suffix));

        JsonNode links = firstPart(saveCanvas(owner, site, part("quicklinks", Map.of("title", "Go", "links",
                List.of(Map.of("label", "Forms", "url", "/sites/x/Templates")))), 200));
        assertThat(links.get("links").get(0).get("label").asText()).isEqualTo("Forms");
        saveCanvas(owner, site, part("quicklinks", Map.of("links", List.of(Map.of("label", "Evil", "url", "javascript:alert(1)")))), 400);

        JsonNode people = firstPart(saveCanvas(owner, site, part("people", Map.of("people",
                List.of(Map.of("login", owner, "displayName", "Owner", "role", "Boss")))), 200));
        assertThat(people.get("people").get(0).get("role").asText()).isEqualTo("Boss");

        UUID events = id(call(owner, jsonBody(post("/api/v1/sites/{id}/lists", site), Map.of("name", "Events", "template", "EVENTS")), 201));
        assertThat(firstPart(saveCanvas(owner, site, part("events", Map.of("listId", events.toString(), "limit", 3)), 200)).get("limit").asInt()).isEqualTo(3);
        // only lists in the same site
        UUID elsewhere = id(call(owner, jsonBody(post("/api/v1/sites/{id}/lists", id(createSite(owner, "parts-b" + suffix))), Map.of("name", "E", "template", "EVENTS")), 201));
        saveCanvas(owner, site, part("events", Map.of("listId", elsewhere.toString())), 400);
    }

    @Test
    void embedsAreLimitedToAllowedSites() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "embed-" + suffix));

        JsonNode youtube = firstPart(saveCanvas(owner, site, part("embed", Map.of("url", "https://www.youtube.com/watch?v=abc123xyz")), 200));
        assertThat(youtube.get("url").asText()).isEqualTo("https://www.youtube-nocookie.com/embed/abc123xyz");

        for (String bad : List.of("https://evil.example/x", "http://www.google.com/maps", "javascript:alert(1)", "https://www.google.com.evil.example/",
                "https://www.google.com@evil.example/", "https://someone@www.google.com/maps", "data:text/html,<script>alert(1)</script>")) {
            saveCanvas(owner, site, part("embed", Map.of("url", bad)), 400);
        }

        call(owner, jsonBody(put("/api/v1/admin/embed-hosts"), List.of("forms.example.com")), 403);
        List<String> before = json.convertValue(call(owner, get("/api/v1/embed-hosts"), 200), List.class);
        call(ADMIN, jsonBody(put("/api/v1/admin/embed-hosts"), List.of("not a host")), 400);
        call(ADMIN, jsonBody(put("/api/v1/admin/embed-hosts"), List.of("forms.example.com")), 204);
        try {
            saveCanvas(owner, site, part("embed", Map.of("url", "https://forms.example.com/f/1")), 200);
            saveCanvas(owner, site, part("embed", Map.of("url", "https://sub.forms.example.com/f/1")), 400);
        } finally {
            call(ADMIN, jsonBody(put("/api/v1/admin/embed-hosts"), before), 204);
        }
    }

    // ---- page templates

    @Test
    void pagesStartFromTemplates() throws Exception {
        String owner = createUser("owner");
        String visitor = createUser("visitor");
        UUID site = id(createSite(owner, "tpl-" + suffix));
        addToGroup(siteGroup(site, "visitors"), visitor);

        JsonNode offered = call(owner, get("/api/v1/sites/{id}/page-templates", site), 200);
        assertThat(offered.findValuesAsText("id")).contains("ARTICLE", "DEPARTMENT", "TEAM", "LANDING");
        call(visitor, get("/api/v1/sites/{id}/page-templates", site), 403);

        JsonNode department = call(owner, jsonBody(post("/api/v1/sites/{id}/pages", site), Map.of("title", "Finance", "kind", "PAGE", "template", "DEPARTMENT")), 201);
        assertThat(department.get("draft").get("canvas").get("sections")).hasSize(3);
        assertThat(department.get("draft").get("canvas").toString()).contains("quicklinks").contains("people").contains("events");
        UUID pageId = id(department.get("page"));
        call(owner, post("/api/v1/pages/{id}/publish", pageId), 200); // built-in layouts are valid pages

        JsonNode saved = call(owner, jsonBody(post("/api/v1/pages/{id}/save-as-template", pageId), Map.of("title", "Our layout")), 201);
        UUID template = id(saved.get("page"));
        JsonNode custom = null;
        for (JsonNode t : call(owner, get("/api/v1/sites/{id}/page-templates", site), 200)) if (t.get("custom").asBoolean()) custom = t;
        assertThat(custom.get("title").asText()).isEqualTo("Our layout");

        JsonNode fromSaved = call(owner, jsonBody(post("/api/v1/sites/{id}/pages", site), Map.of("title", "Sales", "template", template.toString())), 201);
        assertThat(fromSaved.get("draft").get("canvas").get("sections")).hasSize(3);

        // a template is no page: not listed, not published, not seen by readers
        assertThat(call(owner, get("/api/v1/sites/{id}/pages", site), 200).findValuesAsText("title")).doesNotContain("Our layout");
        call(owner, post("/api/v1/pages/{id}/publish", template), 400);
        call(visitor, get("/api/v1/pages/{id}", template), 404);

        call(owner, jsonBody(post("/api/v1/sites/{id}/pages", site), Map.of("title", "X", "template", "NO_SUCH")), 404);
        call(owner, jsonBody(post("/api/v1/sites/{id}/pages", site), Map.of("title", "X", "kind", "TEMPLATE")), 400);
        UUID otherSite = id(createSite(owner, "tpl-b" + suffix));
        call(owner, jsonBody(post("/api/v1/sites/{id}/pages", otherSite), Map.of("title", "X", "template", template.toString())), 404);
    }

    @Test
    void anAttachedFileInASubsiteListWorks() throws Exception {
        String owner = createUser("owner");
        UUID sub = id(subsite(owner, id(createSite(owner, "att-" + suffix)), "team", null, 201));
        UUID list = id(call(owner, jsonBody(post("/api/v1/sites/{id}/lists", sub), Map.of("name", "Tasks", "template", "CUSTOM")), 201));
        UUID item = id(call(owner, jsonBody(post("/api/v1/nodes/{id}/items", list), Map.of("title", "One")), 201));
        MockMultipartFile f = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        JsonNode added = call(owner, multipart("/api/v1/nodes/{id}/files", item).file(f), 201);
        assertThat(added.get(0).get("path").asText()).contains("/team/Tasks/");
    }
}
