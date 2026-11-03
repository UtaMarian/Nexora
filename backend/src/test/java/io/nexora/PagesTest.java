package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Modern pages: drafts and publishing, web part validation, sanitising, news, home page and permissions. */
class PagesTest extends ApiTestSupport {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    private record Setup(UUID site, String owner, String member, String visitor, String path) {}

    private Setup setup() throws Exception {
        String owner = createUser("owner");
        String member = createUser("member");
        String visitor = createUser("visitor");
        String url = "pages-" + suffix;
        UUID site = id(createSite(owner, url));
        addToGroup(siteGroup(site, "members"), member);
        addToGroup(siteGroup(site, "visitors"), visitor);
        return new Setup(site, owner, member, visitor, "/sites/" + url);
    }

    private static Map<String, Object> canvas(Object... webParts) {
        return Map.of("sections", List.of(Map.of("layout", "one", "columns", List.of(List.of(webParts)))));
    }

    private static Map<String, Object> text(String id, String html) {
        return Map.of("id", id, "type", "text", "props", Map.of("html", html));
    }

    private JsonNode saveDraft(String login, JsonNode page, Map<String, Object> canvas, int expected) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", page.get("page").get("title").asText());
        body.put("revision", page.get("revision").asInt());
        body.put("canvas", canvas);
        return call(login, jsonBody(put("/api/v1/pages/{id}/draft", id(page.get("page"))), body), expected);
    }

    @Test
    void draftsStayPrivateUntilPublished() throws Exception {
        Setup s = setup();
        JsonNode page = call(s.member(), jsonBody(post("/api/v1/sites/{id}/pages", s.site()),
                Map.of("title", "Welcome to the Team!", "kind", "PAGE")), 201);
        UUID pageId = id(page.get("page"));
        assertThat(page.get("page").get("name").asText()).isEqualTo("welcome-to-the-team");
        assertThat(page.get("page").get("url").asText()).isEqualTo(s.path() + "/SitePages/welcome-to-the-team.aspx");
        assertThat(page.get("page").get("published").asBoolean()).isFalse();

        // visitors do not see unpublished pages at all
        call(s.visitor(), get("/api/v1/pages/{id}", pageId), 404);
        assertThat(call(s.visitor(), get("/api/v1/sites/{id}/pages", s.site()), 200)).isEmpty();
        call(s.visitor(), jsonBody(post("/api/v1/sites/{id}/pages", s.site()), Map.of("title", "Nope")), 403);

        page = saveDraft(s.member(), page, canvas(text("t1", "<p>Hello <strong>team</strong></p>")), 200);
        call(s.member(), post("/api/v1/pages/{id}/publish", pageId), 200);

        JsonNode seen = call(s.visitor(), get("/api/v1/pages/resolve").param("path", s.path() + "/SitePages/Welcome-To-The-Team.aspx"), 200);
        assertThat(seen.get("published").get("canvas").get("sections").get(0).get("columns").get(0).get(0)
                .get("props").get("html").asText()).isEqualTo("<p>Hello <strong>team</strong></p>");
        assertThat(seen.path("draft").isMissingNode() || seen.path("draft").isNull()).isTrue();
        assertThat(seen.get("canEdit").asBoolean()).isFalse();
        assertThat(seen.get("page").get("excerpt").asText()).isEqualTo("Hello team");

        // a new draft does not change what readers see until it is published
        page = call(s.member(), get("/api/v1/pages/{id}", pageId), 200);
        saveDraft(s.member(), page, canvas(text("t1", "<p>Second edition</p>")), 200);
        assertThat(call(s.visitor(), get("/api/v1/pages/{id}", pageId), 200).get("page").get("excerpt").asText())
                .isEqualTo("Hello team");
        call(s.visitor(), post("/api/v1/pages/{id}/publish", pageId), 403);
        call(s.member(), post("/api/v1/pages/{id}/publish", pageId), 200);
        assertThat(call(s.visitor(), get("/api/v1/pages/{id}", pageId), 200).get("page").get("excerpt").asText())
                .isEqualTo("Second edition");

        // versions and restore (restores into the draft)
        JsonNode versions = call(s.member(), get("/api/v1/pages/{id}/versions", pageId), 200);
        assertThat(versions).extracting(v -> v.get("version").asInt()).containsExactly(2, 1);
        JsonNode restored = call(s.member(), post("/api/v1/pages/{id}/versions/1/restore", pageId), 200);
        assertThat(restored.get("draft").get("canvas").toString()).contains("Hello");
        call(s.member(), post("/api/v1/pages/{id}/discard", pageId), 200);
        call(s.member(), post("/api/v1/pages/{id}/publish", pageId), 409);
    }

    @Test
    void concurrentEditsAreDetected() throws Exception {
        Setup s = setup();
        JsonNode page = call(s.owner(), jsonBody(post("/api/v1/sites/{id}/pages", s.site()), Map.of("title", "Plans")), 201);
        saveDraft(s.owner(), page, canvas(text("t1", "<p>mine</p>")), 200);
        // same stale revision again: someone else saved in between
        saveDraft(s.member(), page, canvas(text("t1", "<p>theirs</p>")), 409);
    }

    @Test
    void richTextIsSanitised() throws Exception {
        Setup s = setup();
        JsonNode page = call(s.owner(), jsonBody(post("/api/v1/sites/{id}/pages", s.site()), Map.of("title", "XSS")), 201);
        String evil = "<p onclick=\"x()\">Hi<script>alert(1)</script><img src=x onerror=alert(1)>"
                + "<a href=\"javascript:alert(1)\">bad</a> <a href=\"/sites/hr\">ok</a> <a href=\"https://example.org\">ext</a></p>"
                + "<iframe src=\"https://evil.example\"></iframe><h2 style=\"color:red\">Head</h2>";
        JsonNode saved = saveDraft(s.owner(), page, canvas(text("t1", evil)), 200);
        String html = saved.get("draft").get("canvas").get("sections").get(0).get("columns").get(0).get(0)
                .get("props").get("html").asText();
        assertThat(html).doesNotContain("script", "onclick", "onerror", "<img", "javascript", "iframe", "style");
        assertThat(html).contains("<a href=\"/sites/hr\" rel=\"noopener noreferrer\">ok</a>")
                .contains("<a href=\"https://example.org\" rel=\"noopener noreferrer\">ext</a>")
                .contains("<h2>Head</h2>");
    }

    @Test
    void webPartsAreValidatedAndScopedToTheSite() throws Exception {
        Setup s = setup();
        UUID library = defaultLibrary(s.owner(), s.site());
        JsonNode page = call(s.owner(), jsonBody(post("/api/v1/sites/{id}/pages", s.site()), Map.of("title", "Parts")), 201);

        page = saveDraft(s.owner(), page, canvas(
                Map.of("id", "d1", "type", "documents", "props", Map.of("libraryId", library.toString(), "limit", 500)),
                Map.of("id", "n1", "type", "news", "props", Map.of())), 200);
        JsonNode parts = page.get("draft").get("canvas").get("sections").get(0).get("columns").get(0);
        assertThat(parts.get(0).get("props").get("limit").asInt()).isEqualTo(50);   // clamped
        assertThat(parts.get(1).get("props").get("layout").asText()).isEqualTo("cards");   // default

        saveDraft(s.owner(), page, canvas(Map.of("id", "x", "type", "script", "props", Map.of())), 400);
        saveDraft(s.owner(), page, canvas(text("a", ""), text("a", "")), 400);
        saveDraft(s.owner(), page, Map.of("sections", List.of(Map.of("layout", "two",
                "columns", List.of(List.of(), List.of(), List.of())))), 400);

        // a library from another site cannot be embedded
        String other = createUser("other");
        UUID otherSite = id(createSite(other, "other-" + suffix));
        UUID foreign = defaultLibrary(other, otherSite);
        saveDraft(s.owner(), page, canvas(Map.of("id", "d1", "type", "documents",
                "props", Map.of("libraryId", foreign.toString()))), 400);
    }

    @Test
    void newsHomePageAndImages() throws Exception {
        Setup s = setup();
        // images go to the Site Assets library and only real images are accepted
        JsonNode image = call(s.member(), multipart("/api/v1/sites/{id}/page-images", s.site())
                .file(new MockMultipartFile("file", "banner.png", "image/png", PNG)), 201);
        assertThat(image.get("path").asText()).isEqualTo(s.path() + "/SiteAssets/banner.png");
        JsonNode again = call(s.member(), multipart("/api/v1/sites/{id}/page-images", s.site())
                .file(new MockMultipartFile("file", "banner.png", "image/png", PNG)), 201);
        assertThat(again.get("name").asText()).isEqualTo("banner-2.png");
        call(s.member(), multipart("/api/v1/sites/{id}/page-images", s.site())
                .file(new MockMultipartFile("file", "x.png", "image/png", "<svg onload=alert(1)>".getBytes())), 400);

        JsonNode news = call(s.member(), jsonBody(post("/api/v1/sites/{id}/pages", s.site()),
                Map.of("title", "Quarterly results", "kind", "NEWS")), 201);
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Quarterly results");
        body.put("revision", news.get("revision").asInt());
        body.put("bannerImageId", image.get("id").asText());
        body.put("canvas", canvas(text("t", "<p>Revenue is up.</p>"),
                Map.of("id", "i", "type", "image", "props", Map.of("fileId", image.get("id").asText(), "alt", "Chart"))));
        call(s.member(), jsonBody(put("/api/v1/pages/{id}/draft", id(news.get("page"))), body), 200);
        call(s.member(), post("/api/v1/pages/{id}/publish", id(news.get("page"))), 200);

        JsonNode feed = call(s.visitor(), get("/api/v1/sites/{id}/news", s.site()), 200);
        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).get("title").asText()).isEqualTo("Quarterly results");
        assertThat(feed.get(0).get("bannerImageId").asText()).isEqualTo(image.get("id").asText());

        // the home page: owners only, published pages only
        JsonNode home = call(s.owner(), jsonBody(post("/api/v1/sites/{id}/pages", s.site()), Map.of("title", "Home")), 201);
        UUID homeId = id(home.get("page"));
        call(s.owner(), jsonBody(put("/api/v1/sites/{id}/home-page", s.site()), Map.of("pageId", homeId.toString())), 409);
        call(s.owner(), post("/api/v1/pages/{id}/publish", homeId), 200);
        call(s.member(), jsonBody(put("/api/v1/sites/{id}/home-page", s.site()), Map.of("pageId", homeId.toString())), 403);
        call(s.owner(), jsonBody(put("/api/v1/sites/{id}/home-page", s.site()), Map.of("pageId", homeId.toString())), 204);
        assertThat(call(s.visitor(), get("/api/v1/sites/{id}/design", s.site()), 200).get("homePageId").asText())
                .isEqualTo(homeId.toString());

        // deleting the home page falls back to the built-in home
        call(s.visitor(), delete("/api/v1/pages/{id}", homeId), 403);
        call(s.member(), delete("/api/v1/pages/{id}", homeId), 204);
        assertThat(call(s.visitor(), get("/api/v1/sites/{id}/design", s.site()), 200).path("homePageId").isMissingNode()).isTrue();
    }

    @Test
    void sitePagesNameIsReserved() throws Exception {
        Setup s = setup();
        call(s.owner(), jsonBody(post("/api/v1/sites/{id}/libraries", s.site()), Map.of("name", "SitePages")), 409);
    }
}
