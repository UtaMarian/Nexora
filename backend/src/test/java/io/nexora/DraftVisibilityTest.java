package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.alerts.AlertService;
import io.nexora.mail.OutboxRepository;
import io.nexora.office.WopiDiscovery;
import io.nexora.office.WopiTokenService;
import io.nexora.search.SearchIndexer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * "Only people who can edit see drafts". Each test is a way a reader might reach a draft, because one forgotten path
 * is all it takes: content, listings, history, search, links, WebDAV, the live editor, email and approvals.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DraftVisibilityTest extends ApiTestSupport {
    @LocalServerPort int port;
    @Autowired WopiTokenService tokens;
    @Autowired SearchIndexer indexer;
    @Autowired AlertService alerts;
    @Autowired OutboxRepository outbox;
    @MockitoBean WopiDiscovery discovery;

    private record Lib(String owner, String member, String reader, String email, UUID site, UUID id, String path) {}

    private Lib library(String visibility) throws Exception {
        String owner = createUser("owner");
        String member = createUser("member");
        String reader = "reader-" + suffix;
        String email = reader + "@example.test";
        call(ADMIN, jsonBody(post("/api/v1/admin/users"), Map.of("login", reader, "displayName", "Reader", "email", email, "password", PW)), 201);
        String url = "drafts-" + UUID.randomUUID().toString().substring(0, 8);
        UUID site = id(createSite(owner, url));
        addToGroup(siteGroup(site, "members"), member);
        addToGroup(siteGroup(site, "visitors"), reader);
        UUID lib = defaultLibrary(owner, site);
        settings(owner, lib, visibility, null);
        return new Lib(owner, member, reader, email, site, lib, "/sites/" + url + "/Shared Documents");
    }

    private void settings(String login, UUID lib, String visibility, Integer maxVersions) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("versioningEnabled", true, "minorVersionsEnabled", true, "requireCheckout", false));
        if (visibility != null) body.put("draftVisibility", visibility);
        if (maxVersions != null) body.put("maxVersions", maxVersions);
        call(login, jsonBody(put("/api/v1/nodes/{id}/settings", lib), body), 200);
    }

    private void publish(String login, UUID file) throws Exception {
        call(login, jsonBody(post("/api/v1/nodes/{id}/publish", file), Map.of()), 200);
    }

    /** A file at 1.1: "published text" is 1.0 and "secret draft" is the newer draft. */
    private UUID withDraft(Lib l, String name) throws Exception {
        UUID file = id(upload(l.member(), l.id(), name, "published text", false, 201));
        publish(l.member(), file);
        upload(l.member(), l.id(), name, "secret draft, longer than that", true, 201);
        return file;
    }

    private List<String> names(String login, UUID parent) throws Exception {
        return call(login, get("/api/v1/nodes/{id}/children", parent), 200).findValuesAsText("name");
    }

    private JsonNode row(String login, UUID parent, String name) throws Exception {
        for (JsonNode n : call(login, get("/api/v1/nodes/{id}/children", parent), 200)) {
            if (n.get("name").asText().equals(name)) return n;
        }
        throw new AssertionError("Not listed: " + name);
    }

    // ---- the setting -----------------------------------------------------------------------------

    @Test
    void anyoneWhoCanReadSeesDraftsUntilALibrarySaysOtherwise() throws Exception {
        Lib l = library(null);
        assertThat(call(l.owner(), get("/api/v1/nodes/{id}/settings", l.id()), 200).get("draftVisibility").asText()).isEqualTo("READERS");
        UUID file = withDraft(l, "open.txt");
        assertThat(download(l.reader(), file)).startsWith("secret draft");
    }

    @Test
    void anOlderClientSavingSettingsCannotReopenDrafts() throws Exception {
        Lib l = library("EDITORS");
        settings(l.owner(), l.id(), null, 100);
        assertThat(call(l.owner(), get("/api/v1/nodes/{id}/settings", l.id()), 200).get("draftVisibility").asText()).isEqualTo("EDITORS");
    }

    @Test
    void theSettingNeedsTheRightToManageTheLibrary() throws Exception {
        Lib l = library("EDITORS");
        call(l.reader(), jsonBody(put("/api/v1/nodes/{id}/settings", l.id()), Map.of("versioningEnabled", true,
                "minorVersionsEnabled", true, "requireCheckout", false, "draftVisibility", "READERS")), 403);
    }

    @Test
    void itOnlyAppliesToLibrariesWithMinorVersions() throws Exception {
        Lib l = library("EDITORS");
        call(l.owner(), jsonBody(put("/api/v1/nodes/{id}/settings", l.id()), Map.of("versioningEnabled", true,
                "minorVersionsEnabled", false, "requireCheckout", false, "draftVisibility", "EDITORS")), 200);
        UUID file = id(upload(l.member(), l.id(), "plain.txt", "text", false, 201));
        assertThat(download(l.reader(), file)).isEqualTo("text");
    }

    // ---- files that were never published ---------------------------------------------------------

    @Test
    void aFileThatWasNeverPublishedDoesNotExistForReaders() throws Exception {
        Lib l = library("EDITORS");
        UUID file = id(upload(l.member(), l.id(), "new.txt", "secret draft", false, 201));

        call(l.reader(), get("/api/v1/nodes/{id}", file), 404);
        call(l.reader(), get("/api/v1/nodes/{id}/content", file), 404);
        call(l.reader(), get("/api/v1/nodes/{id}/versions", file), 404);
        call(l.reader(), get("/api/v1/resolve").param("path", l.path() + "/new.txt"), 404);
        assertThat(names(l.reader(), l.id())).doesNotContain("new.txt");

        assertThat(names(l.member(), l.id())).contains("new.txt");
        assertThat(download(l.owner(), file)).isEqualTo("secret draft");
        assertThat(download(ADMIN, file)).isEqualTo("secret draft");

        publish(l.member(), file);
        assertThat(names(l.reader(), l.id())).contains("new.txt");
        assertThat(download(l.reader(), file)).isEqualTo("secret draft");
    }

    // ---- files with a published version ----------------------------------------------------------

    @Test
    void readersGetThePublishedVersionAndEditorsTheDraft() throws Exception {
        Lib l = library("EDITORS");
        UUID file = withDraft(l, "doc.txt");
        assertThat(download(l.reader(), file)).isEqualTo("published text");
        assertThat(download(l.member(), file)).startsWith("secret draft");
    }

    @Test
    void nothingAboutTheDraftShowsInAListing() throws Exception {
        Lib l = library("EDITORS");
        UUID file = id(upload(l.member(), l.id(), "doc.txt", "published text", false, 201));
        publish(l.member(), file);
        JsonNode published = row(l.reader(), l.id(), "doc.txt");
        upload(l.owner(), l.id(), "doc.txt", "secret draft, longer than that", true, 201);

        JsonNode reader = row(l.reader(), l.id(), "doc.txt");
        assertThat(reader.get("version").asText()).isEqualTo("1.0");
        assertThat(reader.get("sizeBytes").asLong()).isEqualTo("published text".length());
        assertThat(reader.get("modifiedAt")).isEqualTo(published.get("modifiedAt"));
        assertThat(reader.get("modifiedBy").get("login").asText()).isEqualTo(l.member());

        JsonNode editor = row(l.member(), l.id(), "doc.txt");
        assertThat(editor.get("version").asText()).isEqualTo("1.1");
        assertThat(editor.get("modifiedBy").get("login").asText()).isEqualTo(l.owner());
    }

    @Test
    void historyHidesMinorVersionsAndTheirContent() throws Exception {
        Lib l = library("EDITORS");
        UUID file = withDraft(l, "doc.txt");

        JsonNode mine = call(l.reader(), get("/api/v1/nodes/{id}/versions", file), 200);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("label").asText()).isEqualTo("1.0");
        assertThat(mine.get(0).get("current").asBoolean()).isTrue();

        JsonNode all = call(l.member(), get("/api/v1/nodes/{id}/versions", file), 200);
        assertThat(all.findValuesAsText("label")).contains("1.1", "1.0", "0.1");
        for (JsonNode v : all) {
            if (v.get("label").asText().equals("1.0")) continue;
            call(l.reader(), get("/api/v1/nodes/{id}/content", file).param("versionId", v.get("id").asText()), 404);
        }
    }

    @Test
    void readersCanStillOpenOlderPublishedVersions() throws Exception {
        Lib l = library("EDITORS");
        UUID file = withDraft(l, "doc.txt");
        publish(l.member(), file);
        upload(l.member(), l.id(), "doc.txt", "another draft", true, 201);
        JsonNode mine = call(l.reader(), get("/api/v1/nodes/{id}/versions", file), 200);
        assertThat(mine.findValuesAsText("label")).containsExactly("2.0", "1.0");
        for (JsonNode v : mine) {
            MvcResult r = mvc.perform(as(l.reader(), get("/api/v1/nodes/{id}/content", file).param("versionId", v.get("id").asText()))).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test
    void trimmingOldVersionsNeverRemovesTheOnlyPublishedOne() throws Exception {
        Lib l = library("EDITORS");
        settings(l.owner(), l.id(), "EDITORS", 2);
        UUID file = withDraft(l, "doc.txt");
        for (int i = 0; i < 4; i++) upload(l.member(), l.id(), "doc.txt", "draft " + i, true, 201);
        assertThat(download(l.reader(), file)).isEqualTo("published text");
    }

    @Test
    void movingAFileIntoSuchALibraryHidesItsDraftThere() throws Exception {
        Lib l = library("EDITORS");
        JsonNode open = call(l.owner(), jsonBody(post("/api/v1/sites/{id}/libraries", l.site()), Map.of("name", "Open")), 201);
        settings(l.owner(), id(open), "READERS", null);
        UUID file = id(upload(l.member(), id(open), "moved.txt", "secret draft", false, 201));
        assertThat(download(l.reader(), file)).isEqualTo("secret draft");
        call(l.member(), jsonBody(post("/api/v1/nodes/{id}/move", file), Map.of("targetParentId", l.id().toString())), 200);
        call(l.reader(), get("/api/v1/nodes/{id}", file), 404);
    }

    // ---- search ----------------------------------------------------------------------------------

    private int found(String login, String word) throws Exception {
        return call(login, get("/api/v1/search").param("q", word), 200).get("total").asInt();
    }

    private static String word(String base) {
        return base + UUID.randomUUID().toString().replaceAll("[\\d-]", "");
    }

    @Test
    void searchFindsOnlyPublishedTextEvenForEditorsAndSnippetsCarryNoDraft() throws Exception {
        Lib l = library("EDITORS");
        String old = word("publishedword");
        String secret = word("draftword");
        UUID file = id(upload(l.member(), l.id(), "doc.txt", old, false, 201));
        publish(l.member(), file);
        upload(l.member(), l.id(), "doc.txt", secret, true, 201);
        indexer.drain();
        indexer.drain();

        assertThat(found(l.reader(), old)).isEqualTo(1);
        assertThat(found(l.reader(), secret)).isZero();
        assertThat(found(l.member(), secret)).as("the index holds one copy, the one readers may see").isZero();
        assertThat(call(l.reader(), get("/api/v1/search").param("q", old), 200).toString()).doesNotContain(secret);

        publish(l.member(), file);
        indexer.drain();
        indexer.drain();
        assertThat(found(l.reader(), secret)).isEqualTo(1);
    }

    @Test
    void aFileThatWasNeverPublishedIsNotFoundEvenByName() throws Exception {
        Lib l = library("EDITORS");
        String name = word("unpublishedname");
        upload(l.member(), l.id(), name + ".txt", "text", false, 201);
        indexer.drain();
        assertThat(found(l.reader(), name)).isZero();
        assertThat(found(l.member(), name)).isZero();
    }

    @Test
    void changingTheSettingRewritesTheIndex() throws Exception {
        Lib l = library("READERS");
        String secret = word("draftword");
        UUID file = id(upload(l.member(), l.id(), "doc.txt", "published text", false, 201));
        publish(l.member(), file);
        upload(l.member(), l.id(), "doc.txt", secret, true, 201);
        indexer.drain();
        indexer.drain();
        assertThat(found(l.reader(), secret)).isEqualTo(1);

        settings(l.owner(), l.id(), "EDITORS", null);
        indexer.drain();
        indexer.drain();
        assertThat(found(l.reader(), secret)).isZero();
    }

    // ---- links -----------------------------------------------------------------------------------

    private String anyoneLink(String login, UUID node) throws Exception {
        JsonNode link = call(login, jsonBody(post("/api/v1/nodes/{id}/share-links", node), Map.of("scope", "ANYONE", "days", 5)), 201);
        return link.get("token").asText();
    }

    @Test
    void aLinkServesThePublishedVersion() throws Exception {
        Lib l = library("EDITORS");
        UUID file = withDraft(l, "doc.txt");
        String token = anyoneLink(l.member(), file);
        MvcResult open = mvc.perform(get("/api/v1/links/{t}", token)).andReturn();
        assertThat(json.readTree(open.getResponse().getContentAsString()).get("sizeBytes").asLong()).isEqualTo("published text".length());
        assertThat(mvc.perform(get("/api/v1/links/{t}/content", token)).andReturn().getResponse().getContentAsString()).isEqualTo("published text");
    }

    @Test
    void aLinkToAFileThatWasNeverPublishedShowsNothing() throws Exception {
        Lib l = library("EDITORS");
        UUID file = id(upload(l.member(), l.id(), "new.txt", "secret draft", false, 201));
        String token = anyoneLink(l.member(), file);
        assertThat(mvc.perform(get("/api/v1/links/{t}/content", token)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/links/{t}", token)).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aFolderLinkLeavesOutFilesThatWereNeverPublished() throws Exception {
        Lib l = library("EDITORS");
        UUID folder = id(call(l.member(), jsonBody(post("/api/v1/nodes/{id}/folders", l.id()), Map.of("name", "Shared")), 201));
        UUID hidden = id(upload(l.member(), folder, "new.txt", "secret draft", false, 201));
        UUID shown = id(upload(l.member(), folder, "done.txt", "published text", false, 201));
        publish(l.member(), shown);
        String token = anyoneLink(l.member(), folder);

        JsonNode opened = json.readTree(mvc.perform(get("/api/v1/links/{t}", token)).andReturn().getResponse().getContentAsString());
        assertThat(opened.get("entries").findValuesAsText("name")).containsExactly("done.txt");
        assertThat(mvc.perform(get("/api/v1/links/{t}/content", token).param("file", hidden.toString())).andReturn().getResponse().getStatus()).isNotEqualTo(200);
    }

    // ---- WebDAV ----------------------------------------------------------------------------------

    private HttpResponse<String> dav(String login, String method, String path, String... headers) throws Exception {
        StringBuilder url = new StringBuilder("http://localhost:" + port + "/dav");
        for (String seg : path.split("/")) if (!seg.isEmpty()) url.append('/').append(UriUtils.encodePathSegment(seg, StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url.toString())).method(method, HttpRequest.BodyPublishers.noBody())
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((login + ":" + PW).getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aMappedDriveDescribesExactlyWhatItServes() throws Exception {
        Lib l = library("EDITORS");
        withDraft(l, "doc.txt");
        upload(l.member(), l.id(), "new.txt", "secret draft", false, 201);

        HttpResponse<String> listing = dav(l.reader(), "PROPFIND", l.path(), "Depth", "1");
        assertThat(listing.body()).contains("doc.txt").doesNotContain("new.txt")
                .contains("<D:getcontentlength>" + "published text".length() + "</D:getcontentlength>");
        assertThat(dav(l.reader(), "GET", l.path() + "/doc.txt").body()).isEqualTo("published text");
        assertThat(dav(l.reader(), "GET", l.path() + "/new.txt").statusCode()).isEqualTo(404);
        assertThat(dav(l.member(), "GET", l.path() + "/doc.txt").body()).startsWith("secret draft");
    }

    // ---- the live editor -------------------------------------------------------------------------

    private String token(String login, UUID file) throws Exception {
        return tokens.issue(principalId(login), file, "http://localhost:8088");
    }

    private MvcResult wopi(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn();
    }

    @Test
    void readersOpenASeparateReadOnlySessionAndEditorsTheLiveOne() throws Exception {
        when(discovery.available()).thenReturn(true);
        when(discovery.urlFor(anyString(), anyString())).thenReturn(Optional.of("http://office.test/edit?"));
        Lib l = library("EDITORS");
        UUID file = withDraft(l, "doc.txt");

        String reader = call(l.reader(), jsonBody(post("/api/v1/nodes/{id}/office-session", file), Map.of("origin", "http://localhost:8088")), 200).toString();
        String editor = call(l.member(), jsonBody(post("/api/v1/nodes/{id}/office-session", file), Map.of("origin", "http://localhost:8088")), 200).toString();
        assertThat(reader).contains("wopi%2Fpublished%2F" + file).doesNotContain("wopi%2Ffiles");
        assertThat(editor).contains("wopi%2Ffiles%2F" + file);
    }

    @Test
    void aReaderCannotJoinTheLiveSessionByAskingForItsAddress() throws Exception {
        Lib l = library("EDITORS");
        UUID file = withDraft(l, "doc.txt");
        String token = token(l.reader(), file);
        assertThat(wopi(get("/wopi/files/{id}", file).param("access_token", token)).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void theReadOnlySessionLoadsThePublishedVersionAndCannotSave() throws Exception {
        Lib l = library("EDITORS");
        UUID file = withDraft(l, "doc.txt");
        String token = token(l.reader(), file);

        JsonNode info = json.readTree(wopi(get("/wopi/published/{id}", file).param("access_token", token)).getResponse().getContentAsString());
        assertThat(info.get("UserCanWrite").asBoolean()).isFalse();
        assertThat(info.get("Size").asLong()).isEqualTo("published text".length());
        assertThat(wopi(get("/wopi/published/{id}/contents", file).param("access_token", token)).getResponse().getContentAsString())
                .isEqualTo("published text");
        assertThat(wopi(post("/wopi/published/{id}/contents", file).param("access_token", token).content("x".getBytes())).getResponse().getStatus())
                .isEqualTo(405);
    }

    @Test
    void aTokenCannotReachANeverPublishedFile() throws Exception {
        Lib l = library("EDITORS");
        UUID file = id(upload(l.member(), l.id(), "new.txt", "secret draft", false, 201));
        String reader = token(l.reader(), file);
        String editor = token(l.member(), file);
        assertThat(wopi(get("/wopi/published/{id}", file).param("access_token", reader)).getResponse().getStatus()).isEqualTo(404);
        assertThat(wopi(get("/wopi/published/{id}/contents", file).param("access_token", reader)).getResponse().getStatus()).isEqualTo(404);
        assertThat(wopi(get("/wopi/published/{id}/contents", file).param("access_token", editor)).getResponse().getStatus()).isEqualTo(404);
        assertThat(wopi(get("/wopi/files/{id}/contents", file).param("access_token", reader)).getResponse().getStatus()).isEqualTo(404);
    }

    // ---- email and approvals ---------------------------------------------------------------------

    @Test
    void alertsDoNotAnnounceDraftsToReaders() throws Exception {
        Lib l = library("EDITORS");
        UUID file = id(upload(l.member(), l.id(), "doc.txt", "published text", false, 201));
        publish(l.member(), file);
        call(l.reader(), jsonBody(put("/api/v1/nodes/{id}/alert", l.id()), Map.of("frequency", "IMMEDIATE")), 200);
        alerts.match(Instant.now().plusSeconds(5));
        int before = outbox.findByRecipient(l.email()).size();

        upload(l.member(), l.id(), "doc.txt", "secret draft", true, 201);
        alerts.match(Instant.now().plusSeconds(5));
        assertThat(outbox.findByRecipient(l.email())).hasSize(before);
    }

    private JsonNode ask(Lib l, UUID file, String approver, int status) throws Exception {
        List<String> people = new ArrayList<>(List.of(principalId(approver).toString()));
        return call(l.owner(), jsonBody(post("/api/v1/nodes/{id}/approvals", file), Map.of("people", people, "kind", "APPROVAL")), status);
    }

    @Test
    void someoneWhoCannotSeeTheDraftCannotBeAskedToApproveIt() throws Exception {
        Lib l = library("EDITORS");
        UUID file = withDraft(l, "doc.txt");
        assertThat(ask(l, file, l.reader(), 400).get("code").asText()).isEqualTo("no_access");
        ask(l, file, l.member(), 201);
    }
}
