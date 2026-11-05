package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.mail.OutboxMail;
import io.nexora.mail.OutboxRepository;
import io.nexora.office.WopiTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Storage limits on sites: what counts, every way of writing, and the warning to owners. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteQuotaTest extends ApiTestSupport {
    @LocalServerPort int port;
    @Autowired WopiTokenService tokens;
    @Autowired OutboxRepository outbox;

    private record Site(String owner, String url, UUID id, UUID lib) {}

    private Site site() throws Exception {
        return site("owner");
    }

    private Site site(String ownerName) throws Exception {
        String owner = createUser(ownerName);
        String url = "quota-" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = id(createSite(owner, url));
        return new Site(owner, url, id, defaultLibrary(owner, id));
    }

    private void limit(UUID site, long max, Long warn, int status) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("maxBytes", max));
        if (warn != null) body.put("warnBytes", warn);
        call(ADMIN, jsonBody(put("/api/v1/admin/sites/{id}/quota", site), body), status);
    }

    private long used(UUID site) throws Exception {
        for (JsonNode r : call(ADMIN, get("/api/v1/admin/sites"), 200)) {
            if (id(r).equals(site)) return r.get("quotaUsedBytes").asLong();
        }
        throw new AssertionError("No site " + site);
    }

    private static String bytes(int n, char c) {
        return String.valueOf(c).repeat(n);
    }

    private void assertFull(JsonNode refused) {
        assertThat(refused.get("code").asText()).isEqualTo("quota_exceeded");
        assertThat(refused.get("detail").asText()).contains("storage limit");
    }

    @Test
    void onlyFarmAdministratorsSetLimits() throws Exception {
        Site s = site();
        call(s.owner(), jsonBody(put("/api/v1/admin/sites/{id}/quota", s.id()), Map.of("maxBytes", 10)), 403);
        call(s.owner(), delete("/api/v1/admin/sites/{id}/quota", s.id()), 403);
    }

    @Test
    void limitsMustMakeSense() throws Exception {
        Site s = site();
        limit(s.id(), 0, null, 400);
        limit(s.id(), -5, null, 400);
        limit(s.id(), 100, 100L, 400);
        limit(s.id(), 100, 500L, 400);
        limit(UUID.randomUUID(), 100, null, 404);
        call(ADMIN, jsonBody(put("/api/v1/admin/sites/{id}/quota", s.id()), Map.of()), 400);
    }

    @Test
    void aSiteWithoutALimitTakesAnything() throws Exception {
        Site s = site();
        upload(s.owner(), s.lib(), "big.txt", bytes(5000, 'a'), false, 201);
    }

    @Test
    void uploadsAreRefusedAtTheLimitAndNothingIsKept() throws Exception {
        Site s = site();
        limit(s.id(), 100, null, 200);
        upload(s.owner(), s.lib(), "fits.txt", bytes(100, 'a'), false, 201);
        assertFull(upload(s.owner(), s.lib(), "over.txt", "b", false, 507));
        assertThat(call(s.owner(), get("/api/v1/nodes/{id}/children", s.lib()), 200).findValuesAsText("name")).containsExactly("fits.txt");
        assertThat(used(s.id())).isEqualTo(100);
    }

    @Test
    void newVersionsCountButIdenticalContentAndPublishingDoNot() throws Exception {
        Site s = site();
        limit(s.id(), 1000, null, 200);
        call(s.owner(), jsonBody(put("/api/v1/nodes/{id}/settings", s.lib()), Map.of("versioningEnabled", true,
                "minorVersionsEnabled", true, "requireCheckout", false)), 200);
        UUID file = id(upload(s.owner(), s.lib(), "a.txt", bytes(100, 'a'), false, 201));
        assertThat(used(s.id())).isEqualTo(100);
        upload(s.owner(), s.lib(), "a.txt", bytes(50, 'b'), true, 201);
        assertThat(used(s.id())).isEqualTo(150);
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/publish", file), Map.of()), 200);
        assertThat(used(s.id())).as("publishing reuses the draft's bytes").isEqualTo(150);
        upload(s.owner(), s.lib(), "copy.txt", bytes(100, 'a'), false, 201);
        assertThat(used(s.id())).as("the same content is stored once").isEqualTo(150);
    }

    @Test
    void anEmptyFileAlwaysFitsSoExplorerCanStartItsTwoStepUpload() throws Exception {
        Site s = site();
        limit(s.id(), 10, null, 200);
        upload(s.owner(), s.lib(), "full.txt", bytes(10, 'a'), false, 201);
        upload(s.owner(), s.lib(), "empty.txt", "", false, 201);
    }

    @Test
    void theRecycleBinCountsUntilItIsEmptied() throws Exception {
        Site s = site();
        limit(s.id(), 100, null, 200);
        UUID file = id(upload(s.owner(), s.lib(), "a.txt", bytes(100, 'a'), false, 201));
        call(s.owner(), delete("/api/v1/nodes/{id}", file), 204);
        assertThat(used(s.id())).isEqualTo(100);
        assertFull(upload(s.owner(), s.lib(), "b.txt", bytes(10, 'b'), false, 507));
        call(s.owner(), delete("/api/v1/recycle-bin/{id}", file), 204);
        assertThat(used(s.id())).isZero();
        upload(s.owner(), s.lib(), "b.txt", bytes(10, 'b'), false, 201);
    }

    @Test
    void subsitesShareTheirParentsLimitAndCannotHaveTheirOwn() throws Exception {
        Site s = site();
        JsonNode sub = call(s.owner(), jsonBody(post("/api/v1/sites/{id}/subsites", s.id()), Map.of("url", "team", "title", "Team")), 201);
        UUID subLib = defaultLibrary(s.owner(), id(sub));
        limit(id(sub), 100, null, 400);
        limit(s.id(), 100, null, 200);
        upload(s.owner(), subLib, "a.txt", bytes(80, 'a'), false, 201);
        assertThat(used(s.id())).isEqualTo(80);
        assertFull(upload(s.owner(), s.lib(), "b.txt", bytes(30, 'b'), false, 507));
        assertFull(upload(s.owner(), subLib, "b.txt", bytes(30, 'b'), false, 507));
    }

    @Test
    void aPrivateDraftCountsOnceAsItChanges() throws Exception {
        Site s = site();
        limit(s.id(), 150, null, 200);
        UUID file = id(upload(s.owner(), s.lib(), "a.txt", bytes(100, 'a'), false, 201));
        call(s.owner(), post("/api/v1/nodes/{id}/checkout", file), 200);
        upload(s.owner(), s.lib(), "a.txt", bytes(40, 'b'), true, 201);
        assertThat(used(s.id())).isEqualTo(140);
        upload(s.owner(), s.lib(), "a.txt", bytes(50, 'c'), true, 201);
        assertThat(used(s.id())).as("the old draft is replaced, not added to").isEqualTo(150);
        assertFull(upload(s.owner(), s.lib(), "a.txt", bytes(51, 'd'), true, 507));
    }

    @Test
    void movingIntoAFullSiteIsRefusedAndWithinASiteIsFree() throws Exception {
        Site from = site("from");
        Site to = site("to");
        addToGroup(siteGroup(to.id(), "owners"), from.owner());
        limit(to.id(), 100, null, 200);
        limit(from.id(), 100, null, 200);
        UUID file = id(upload(from.owner(), from.lib(), "a.txt", bytes(80, 'a'), false, 201));
        upload(to.owner(), to.lib(), "b.txt", bytes(50, 'b'), false, 201);
        JsonNode refused = call(from.owner(), jsonBody(post("/api/v1/nodes/{id}/move", file), Map.of("targetParentId", to.lib().toString())), 507);
        assertFull(refused);

        JsonNode folder = call(from.owner(), jsonBody(post("/api/v1/nodes/{id}/folders", from.lib()), Map.of("name", "F")), 201);
        call(from.owner(), jsonBody(post("/api/v1/nodes/{id}/move", file), Map.of("targetParentId", id(folder).toString())), 200);
    }

    @Test
    void theWarningReachesOwnersOnceUntilUsageFallsBack() throws Exception {
        Site s = site();
        String address = "owner-" + suffix + "@example.test";
        String boss = "boss-" + suffix;
        call(ADMIN, jsonBody(post("/api/v1/admin/users"), Map.of("login", boss, "displayName", "Boss", "email", address, "password", PW)), 201);
        addToGroup(siteGroup(s.id(), "owners"), boss);
        limit(s.id(), 1000, 800L, 200);

        upload(s.owner(), s.lib(), "a.txt", bytes(700, 'a'), false, 201);
        List<OutboxMail> before = outbox.findByRecipient(address);
        assertThat(before).isEmpty();

        UUID big = id(upload(s.owner(), s.lib(), "b.txt", bytes(150, 'b'), false, 201));
        assertThat(outbox.findByRecipient(address)).hasSize(1);
        assertThat(outbox.findByRecipient(address).get(0).getSubject()).contains("85%");

        upload(s.owner(), s.lib(), "c.txt", bytes(10, 'c'), false, 201);
        assertThat(outbox.findByRecipient(address)).as("one warning, not one per upload").hasSize(1);

        call(s.owner(), delete("/api/v1/nodes/{id}", big), 204);
        call(s.owner(), delete("/api/v1/recycle-bin/{id}", big), 204);
        upload(s.owner(), s.lib(), "d.txt", bytes(10, 'd'), false, 201);
        upload(s.owner(), s.lib(), "e.txt", bytes(150, 'e'), false, 201);
        assertThat(outbox.findByRecipient(address)).as("rearmed once usage dropped").hasSize(2);
    }

    @Test
    void removingALimitLetsContentInAgain() throws Exception {
        Site s = site();
        limit(s.id(), 10, null, 200);
        upload(s.owner(), s.lib(), "a.txt", bytes(50, 'a'), false, 507);
        call(ADMIN, delete("/api/v1/admin/sites/{id}/quota", s.id()), 204);
        upload(s.owner(), s.lib(), "a.txt", bytes(50, 'a'), false, 201);
    }

    @Test
    void desktopOfficeGetsInsufficientStorageOverWebDav() throws Exception {
        Site s = site();
        limit(s.id(), 10, null, 200);
        String path = "http://localhost:" + port + "/dav/sites/" + s.url() + "/"
                + UriUtils.encodePathSegment("Shared Documents", StandardCharsets.UTF_8) + "/big.txt";
        HttpRequest put = HttpRequest.newBuilder(URI.create(path))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((s.owner() + ":" + PW).getBytes(StandardCharsets.UTF_8)))
                .PUT(HttpRequest.BodyPublishers.ofString(bytes(50, 'a'))).build();
        HttpResponse<String> r = HttpClient.newHttpClient().send(put, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(507);
    }

    @Test
    void theBrowserEditorGetsInsufficientStorage() throws Exception {
        Site s = site();
        UUID file = id(upload(s.owner(), s.lib(), "Budget.xlsx", "small", false, 201));
        limit(s.id(), 10, null, 200);
        String token = tokens.issue(principalId(s.owner()), file, "http://localhost:8088");
        MvcResult r = mvc.perform(post("/wopi/files/{id}/contents", file).param("access_token", token)
                .content(bytes(50, 'a').getBytes(StandardCharsets.UTF_8))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(507);
    }
}
