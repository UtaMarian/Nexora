package io.nexora.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Search through the real API: every kind of change must reach the index, and nothing private may leak out of it. */
class SearchApiTest extends ApiTestSupport {
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired SearchIndexer indexer;
    @Autowired SearchTextRepository texts;
    @Autowired io.nexora.content.FileVersionRepository versions;

    private record Setup(UUID site, UUID lib, String owner, String visitor, String url) {}

    private Setup setup() throws Exception {
        String owner = createUser("owner");
        String visitor = createUser("visitor");
        String url = "search-" + suffix;
        UUID site = id(createSite(owner, url));
        addToGroup(siteGroup(site, "visitors"), visitor);
        return new Setup(site, defaultLibrary(owner, site), owner, visitor, url);
    }

    /** A word no other test can match, so results stay exact. */
    private String word(String base) {
        return base + suffix.replaceAll("\\d", "x");
    }

    private JsonNode search(String login, String q) throws Exception {
        return search(login, get("/api/v1/search").param("q", q));
    }

    private JsonNode search(String login, MockHttpServletRequestBuilder request) throws Exception {
        indexer.drain();
        return call(login, request, 200);
    }

    private List<String> names(String login, String q) throws Exception {
        return search(login, q).get("hits").findValuesAsText("name");
    }

    private JsonNode uploadDocx(String login, UUID parent, String name, String text) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, DOCX, SearchFixtures.docx(text));
        JsonNode r = call(login, multipart("/api/v1/nodes/{id}/files", parent).file(file).param("overwrite", "true"), 201);
        return r.isArray() ? r.get(0) : r;
    }

    @Test
    void findsTextInsideDocumentsWithASnippet() throws Exception {
        Setup s = setup();
        String w = word("zebrafish");
        uploadDocx(s.owner(), s.lib(), "Report.docx", "Our quarterly " + w + " numbers are looking good");

        JsonNode hit = search(s.visitor(), w).get("hits").get(0);
        assertThat(hit.get("name").asText()).isEqualTo("Report.docx");
        assertThat(hit.get("kind").asText()).isEqualTo("FILE");
        assertThat(hit.get("path").asText()).isEqualTo("/sites/" + s.url() + "/Shared Documents/Report.docx");
        assertThat(hit.get("siteTitle").asText()).isEqualTo("Site " + s.url());
        assertThat(hit.get("modifiedBy").asText()).isEqualTo("owner");
        JsonNode match = null;
        for (JsonNode seg : hit.get("snippet")) if (seg.get("match").asBoolean()) match = seg;
        assertThat(match).isNotNull();
        assertThat(match.get("text").asText()).isEqualToIgnoringCase(w);
    }

    @Test
    void aNewVersionReplacesTheOldText() throws Exception {
        Setup s = setup();
        String before = word("alpaca");
        String after = word("marmot");
        uploadDocx(s.owner(), s.lib(), "Doc.docx", before);
        assertThat(names(s.owner(), before)).containsExactly("Doc.docx");

        uploadDocx(s.owner(), s.lib(), "Doc.docx", after);
        assertThat(names(s.owner(), after)).containsExactly("Doc.docx");
        assertThat(names(s.owner(), before)).isEmpty();
    }

    @Test
    void aCheckedOutDraftStaysOutOfSearch() throws Exception {
        Setup s = setup();
        String published = word("published");
        String draft = word("draftsecret");
        UUID file = id(uploadDocx(s.owner(), s.lib(), "Doc.docx", published));
        call(s.owner(), post("/api/v1/nodes/{id}/checkout", file), 200);
        uploadDocx(s.owner(), s.lib(), "Doc.docx", draft);

        assertThat(names(s.visitor(), draft)).isEmpty();
        assertThat(names(s.owner(), draft)).isEmpty();
        assertThat(names(s.visitor(), published)).containsExactly("Doc.docx");

        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/checkin", file), Map.of("comment", "done")), 200);
        assertThat(names(s.visitor(), draft)).containsExactly("Doc.docx");
    }

    @Test
    void revokingAccessAppliesToTheVeryNextSearch() throws Exception {
        Setup s = setup();
        String w = word("payroll");
        UUID folder = id(call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/folders", s.lib()), Map.of("name", "Secret")), 201));
        uploadDocx(s.owner(), folder, "Salaries.docx", w);
        assertThat(names(s.visitor(), w)).containsExactly("Salaries.docx");

        // the index has not caught up with the new ACL here, so this is the database check alone
        call(s.owner(), post("/api/v1/nodes/{id}/permissions/break-inheritance", folder), 200);
        call(s.owner(), delete("/api/v1/nodes/{id}/permissions/{p}", folder, siteGroup(s.site(), "visitors")), 200);
        JsonNode stale = call(s.visitor(), get("/api/v1/search").param("q", w), 200);
        assertThat(stale.get("hits")).isEmpty();
        assertThat(stale.get("total").asLong()).isZero();

        indexer.drain();
        assertThat(names(s.visitor(), w)).isEmpty();

        UUID visitor = principalId(s.visitor());
        call(s.owner(), jsonBody(put("/api/v1/nodes/{id}/permissions/{p}", folder, visitor), Map.of("level", "READ")), 200);
        assertThat(names(s.visitor(), w)).containsExactly("Salaries.docx");

        // no indexing between the revoke and the search: this must not depend on the queue
        call(s.owner(), delete("/api/v1/nodes/{id}/permissions/{p}", folder, visitor), 200);
        JsonNode r = call(s.visitor(), get("/api/v1/search").param("q", w), 200);
        assertThat(r.get("hits")).isEmpty();
        assertThat(r.get("total").asLong()).isZero();

        call(s.owner(), post("/api/v1/nodes/{id}/permissions/reset-inheritance", folder), 200);
        assertThat(names(s.visitor(), w)).containsExactly("Salaries.docx");
    }

    @Test
    void anUnsearchableUserSeesNothingAndFacetsLeakNothing() throws Exception {
        Setup s = setup();
        String stranger = createUser("stranger");
        String w = word("confidential");
        uploadDocx(s.owner(), s.lib(), "Doc.docx", w);
        JsonNode r = search(stranger, w);
        assertThat(r.get("hits")).isEmpty();
        assertThat(r.get("total").asLong()).isZero();
        assertThat(r.toString()).doesNotContain(s.url());
    }

    @Test
    void followsRenamesMovesDeletesAndRestores() throws Exception {
        Setup s = setup();
        String w = word("wombat");
        UUID folder = id(call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/folders", s.lib()), Map.of("name", "Old")), 201));
        UUID target = id(call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/folders", s.lib()), Map.of("name", "Target")), 201));
        uploadDocx(s.owner(), folder, "Doc.docx", w);
        String base = "/sites/" + s.url() + "/Shared Documents/";
        assertThat(search(s.owner(), w).get("hits").get(0).get("path").asText()).isEqualTo(base + "Old/Doc.docx");

        call(s.owner(), jsonBody(patch("/api/v1/nodes/{id}", folder), Map.of("name", "New")), 200);
        assertThat(search(s.owner(), w).get("hits").get(0).get("path").asText()).isEqualTo(base + "New/Doc.docx");

        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/move", folder), Map.of("targetParentId", target)), 200);
        assertThat(search(s.owner(), w).get("hits").get(0).get("path").asText()).isEqualTo(base + "Target/New/Doc.docx");

        call(s.owner(), delete("/api/v1/nodes/{id}", folder), 204);
        JsonNode gone = search(s.owner(), w);
        assertThat(gone.get("hits")).isEmpty();
        assertThat(gone.get("total").asLong()).isZero();

        call(s.owner(), post("/api/v1/recycle-bin/{id}/restore", folder), 200);
        assertThat(names(s.owner(), w)).containsExactly("Doc.docx");
    }

    @Test
    void aPurgedDocumentLeavesNoTextBehind() throws Exception {
        Setup s = setup();
        String w = word("purgeme");
        UUID file = id(uploadDocx(s.owner(), s.lib(), "Doc.docx", w));
        assertThat(names(s.owner(), w)).containsExactly("Doc.docx");
        String key = versions.findByNodeIdIn(List.of(file)).get(0).getStorageKey();
        assertThat(texts.findById(key)).isPresent();

        call(s.owner(), delete("/api/v1/nodes/{id}", file), 204);
        call(s.owner(), delete("/api/v1/recycle-bin/{id}", file), 204);
        assertThat(names(s.owner(), w)).isEmpty();
        assertThat(texts.findById(key)).isEmpty();
    }

    @Test
    void identicalFilesAreReadOnceAndADeletedCopyKeepsTheTextOfTheOther() throws Exception {
        Setup s = setup();
        String w = word("twins");
        UUID first = id(uploadDocx(s.owner(), s.lib(), "One.docx", w));
        uploadDocx(s.owner(), s.lib(), "Two.docx", w);
        assertThat(names(s.owner(), w)).containsExactlyInAnyOrder("One.docx", "Two.docx");

        call(s.owner(), delete("/api/v1/nodes/{id}", first), 204);
        call(s.owner(), delete("/api/v1/recycle-bin/{id}", first), 204);
        indexer.drain();
        call(ADMIN, post("/api/v1/admin/search/rebuild"), 202);
        assertThat(names(s.owner(), w)).containsExactly("Two.docx");
    }

    @Test
    void findsColumnsTitlesAndDescriptions() throws Exception {
        Setup s = setup();
        String title = word("titleword");
        String column = word("columnword");
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/columns", s.lib()),
                Map.of("displayName", "Department", "type", "TEXT")), 201);
        UUID file = id(upload(s.owner(), s.lib(), "plain.txt", "nothing here", false, 201));
        call(s.owner(), jsonBody(patch("/api/v1/nodes/{id}", file), Map.of("title", title)), 200);
        assertThat(names(s.owner(), title)).containsExactly("plain.txt");

        call(s.owner(), jsonBody(patch("/api/v1/nodes/{id}", file), Map.of("metadata", Map.of("Department", column))), 200);
        assertThat(names(s.owner(), column)).containsExactly("plain.txt");
    }

    @Test
    void findsListItemsAndKnowsTheirLists() throws Exception {
        Setup s = setup();
        String w = word("ticket");
        JsonNode list = call(s.owner(), jsonBody(post("/api/v1/sites/{id}/lists", s.site()),
                Map.of("name", "Issues", "title", "Issues", "template", "CUSTOM")), 201);
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/items", id(list)), Map.of("title", "Printer " + w)), 201);
        JsonNode hit = search(s.visitor(), w).get("hits").get(0);
        assertThat(hit.get("kind").asText()).isEqualTo("LIST_ITEM");
        assertThat(hit.get("title").asText()).isEqualTo("Printer " + w);
    }

    @Test
    void findsPublishedPagesButNeverDrafts() throws Exception {
        Setup s = setup();
        String live = word("liveword");
        String draft = word("draftword");
        JsonNode page = call(s.owner(), jsonBody(post("/api/v1/sites/{id}/pages", s.site()),
                Map.of("title", "Handbook", "kind", "PAGE")), 201);
        UUID pageId = id(page.get("page"));
        savePage(s.owner(), pageId, page.get("revision").asInt(), live);
        assertThat(names(s.owner(), live)).isEmpty();

        call(s.owner(), post("/api/v1/pages/{id}/publish", pageId), 200);
        JsonNode hit = search(s.visitor(), live).get("hits").get(0);
        assertThat(hit.get("kind").asText()).isEqualTo("PAGE");
        assertThat(hit.get("path").asText()).isEqualTo("/sites/" + s.url() + "/SitePages/handbook.aspx");

        JsonNode edited = call(s.owner(), get("/api/v1/pages/{id}", pageId), 200);
        savePage(s.owner(), pageId, edited.get("revision").asInt(), draft);
        assertThat(names(s.owner(), draft)).isEmpty();
        assertThat(names(s.owner(), live)).hasSize(1);

        call(s.owner(), delete("/api/v1/pages/{id}", pageId), 204);
        assertThat(names(s.owner(), live)).isEmpty();
    }

    private void savePage(String login, UUID page, int revision, String text) throws Exception {
        Map<String, Object> part = Map.of("id", "t1", "type", "text", "props", Map.of("html", "<p>" + text + "</p>"));
        Map<String, Object> canvas = Map.of("sections", List.of(Map.of("layout", "one", "columns", List.of(List.of(part)))));
        call(login, jsonBody(put("/api/v1/pages/{id}/draft", page),
                Map.of("title", "Handbook", "revision", revision, "canvas", canvas)), 200);
    }

    @Test
    void filtersAndSortsThroughTheApi() throws Exception {
        Setup s = setup();
        String w = word("facetword");
        uploadDocx(s.owner(), s.lib(), "A.docx", w);
        upload(s.owner(), s.lib(), "B.txt", w, false, 201);

        JsonNode all = search(s.owner(), w);
        assertThat(all.get("total").asLong()).isEqualTo(2);
        assertThat(all.get("facets").get("type").findValuesAsText("value")).containsExactlyInAnyOrder("word", "text");
        JsonNode site = all.get("facets").get("site").get(0);
        assertThat(site.get("value").asText()).isEqualTo(s.url());
        assertThat(site.get("label").asText()).isEqualTo("Site " + s.url());

        assertThat(search(s.owner(), get("/api/v1/search").param("q", w).param("type", "word"))
                .get("hits").findValuesAsText("name")).containsExactly("A.docx");
        assertThat(names(s.owner(), w + " filetype:txt")).containsExactly("B.txt");
        assertThat(search(s.owner(), get("/api/v1/search").param("q", w).param("sort", "modified").param("size", "1").param("page", "1"))
                .get("hits")).hasSize(1);
    }

    @Test
    void anEmptySearchIsEmpty() throws Exception {
        Setup s = setup();
        assertThat(search(s.owner(), "").get("hits")).isEmpty();
    }

    @Test
    void rebuildRestoresTheIndexFromTheDatabaseWithoutRereading() throws Exception {
        Setup s = setup();
        String w = word("rebuilt");
        UUID file = id(uploadDocx(s.owner(), s.lib(), "Doc.docx", w));
        assertThat(names(s.owner(), w)).hasSize(1);
        String key = versions.findByNodeIdIn(List.of(file)).get(0).getStorageKey();
        var before = texts.findById(key).orElseThrow().getExtractedAt();

        call(ADMIN, post("/api/v1/admin/search/rebuild"), 202);
        assertThat(names(s.owner(), w)).hasSize(1);
        assertThat(texts.findById(key).orElseThrow().getExtractedAt()).isEqualTo(before);
    }

    @Test
    void administratorsSeeStatusAndOthersDoNot() throws Exception {
        Setup s = setup();
        JsonNode status = call(ADMIN, get("/api/v1/admin/search"), 200);
        assertThat(status.get("engine").get("ok").asBoolean()).isTrue();
        assertThat(status.get("extractor").get("name").asText()).contains("Built-in");
        call(s.owner(), get("/api/v1/admin/search"), 403);
        call(s.owner(), post("/api/v1/admin/search/rebuild"), 403);

        JsonNode health = call(ADMIN, get("/api/v1/admin/health"), 200);
        assertThat(health.findValuesAsText("component")).contains("Search index", "Document reading");
    }

    @Test
    void administratorsCanTurnSearchOffAndNothingIsLost() throws Exception {
        Setup s = setup();
        String before = word("beforeoff");
        String during = word("duringoff");
        uploadDocx(s.owner(), s.lib(), "Before.docx", before);
        assertThat(names(s.owner(), before)).containsExactly("Before.docx");
        assertThat(call(s.owner(), get("/api/v1/auth/me"), 200).get("searchEnabled").asBoolean()).isTrue();

        // only administrators may flip it
        call(s.owner(), jsonBody(put("/api/v1/admin/search/enabled"), Map.of("enabled", false)), 403);
        call(ADMIN, jsonBody(put("/api/v1/admin/search/enabled"), Map.of("enabled", false)), 204);
        try {
            assertThat(call(s.owner(), get("/api/v1/auth/me"), 200).get("searchEnabled").asBoolean()).isFalse();
            assertThat(call(s.owner(), get("/api/v1/search").param("q", before), 503).get("code").asText()).isEqualTo("search_disabled");
            call(ADMIN, get("/api/v1/search").param("q", before), 503);
            call(ADMIN, post("/api/v1/admin/search/rebuild"), 503);
            assertThat(call(ADMIN, get("/api/v1/admin/search"), 200).get("enabled").asBoolean()).isFalse();
            assertThat(call(ADMIN, get("/api/v1/admin/health"), 200).toString()).contains("DISABLED");

            // documents still work, and the change waits instead of being indexed
            UUID file = id(uploadDocx(s.owner(), s.lib(), "During.docx", during));
            assertThat(download(s.owner(), file)).isNotEmpty();
            indexer.drain();
            assertThat(call(ADMIN, get("/api/v1/admin/search"), 200).get("indexing").asLong()).isPositive();
        } finally {
            call(ADMIN, jsonBody(put("/api/v1/admin/search/enabled"), Map.of("enabled", true)), 204);
        }

        assertThat(names(s.owner(), during)).containsExactly("During.docx");
        assertThat(names(s.owner(), before)).containsExactly("Before.docx");
    }

    @Test
    void aDamagedFileIsListedForAdministratorsButStillFoundByName() throws Exception {
        Setup s = setup();
        String name = word("broken") + ".docx";
        // the zip is valid but its XML is not
        byte[] broken = zipWithBadXml();
        MockMultipartFile file = new MockMultipartFile("file", name, DOCX, broken);
        call(s.owner(), multipart("/api/v1/nodes/{id}/files", s.lib()).file(file), 201);

        assertThat(names(s.owner(), name.replace(".docx", ""))).containsExactly(name);
        JsonNode status = call(ADMIN, get("/api/v1/admin/search"), 200);
        assertThat(status.get("unread").toString()).contains(name);
    }

    private static byte[] zipWithBadXml() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write("<not-closed".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
