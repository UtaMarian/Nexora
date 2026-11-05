package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.mail.OutboxRepository;
import io.nexora.sharing.ShareLink;
import io.nexora.sharing.ShareLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Share links, sharing with people, "Shared with me" and "Recent". */
class SharingTest extends ApiTestSupport {
    @Autowired ShareLinkRepository links;
    @Autowired OutboxRepository outbox;

    private record Setup(UUID site, UUID lib, String owner, String member, String visitor, String stranger) {}

    private Setup setup() throws Exception {
        String owner = createUser("owner");
        String member = createUser("member");
        String visitor = createUser("visitor");
        String stranger = createUser("stranger");
        UUID site = id(createSite(owner, "share-" + suffix));
        addToGroup(siteGroup(site, "members"), member);
        addToGroup(siteGroup(site, "visitors"), visitor);
        return new Setup(site, defaultLibrary(owner, site), owner, member, visitor, stranger);
    }

    private JsonNode link(String login, UUID node, String scope, int days, String password, int status) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("scope", scope, "days", days));
        if (password != null) body.put("password", password);
        return call(login, jsonBody(post("/api/v1/nodes/{id}/share-links", node), body), status);
    }

    /** A request with no credentials at all. */
    private MvcResult anonymous(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private int openStatus(String token) throws Exception {
        return anonymous(get("/api/v1/links/{t}", token)).getResponse().getStatus();
    }

    private String code(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("code").asText();
    }

    @Test
    void anyoneWithTheLinkCanDownloadWithoutSigningIn() throws Exception {
        Setup s = setup();
        UUID file = id(upload(s.member(), s.lib(), "Report.txt", "the report", false, 201));
        String token = link(s.member(), file, "ANYONE", 7, null, 201).get("token").asText();

        MvcResult open = anonymous(get("/api/v1/links/{t}", token));
        assertThat(open.getResponse().getStatus()).isEqualTo(200);
        JsonNode info = json.readTree(open.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(info.get("name").asText()).isEqualTo("Report.txt");
        assertThat(info.get("sharedBy").asText()).isEqualTo("member");

        MvcResult content = anonymous(get("/api/v1/links/{t}/content", token));
        assertThat(content.getResponse().getContentAsString()).isEqualTo("the report");
        assertThat(content.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(content.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

        // the rest of the API stays closed, and only the token's hash is stored
        assertThat(anonymous(get("/api/v1/nodes/{id}/content", file)).getResponse().getStatus()).isEqualTo(401);
        assertThat(links.findAll().stream().map(l -> l.getId().toString())).doesNotContain(token);
        assertThat(links.findByTokenHash(token)).isEmpty();
    }

    @Test
    void aLinkShowsThePublishedVersionNeverADraft() throws Exception {
        Setup s = setup();
        UUID file = id(upload(s.member(), s.lib(), "Doc.txt", "published", false, 201));
        String token = link(s.member(), file, "ANYONE", 7, null, 201).get("token").asText();
        call(s.member(), post("/api/v1/nodes/{id}/checkout", file), 200);
        upload(s.member(), s.lib(), "Doc.txt", "private draft", true, 201);
        assertThat(anonymous(get("/api/v1/links/{t}/content", token)).getResponse().getContentAsString()).isEqualTo("published");
    }

    @Test
    void linksStopWorking() throws Exception {
        Setup s = setup();
        UUID file = id(upload(s.member(), s.lib(), "Doc.txt", "x", false, 201));

        assertThat(openStatus("not-a-real-token")).isEqualTo(404);

        JsonNode revoked = link(s.member(), file, "ANYONE", 7, null, 201);
        call(s.member(), delete("/api/v1/share-links/{id}", id(revoked.get("link"))), 204);
        assertThat(openStatus(revoked.get("token").asText())).isEqualTo(404);

        JsonNode expired = link(s.member(), file, "ANYONE", 7, null, 201);
        ShareLink row = links.findById(id(expired.get("link"))).orElseThrow();
        var field = ShareLink.class.getDeclaredField("expiresAt");
        field.setAccessible(true);
        field.set(row, Instant.now().minusSeconds(1));
        links.save(row);
        assertThat(openStatus(expired.get("token").asText())).isEqualTo(404);

        String deleted = link(s.member(), file, "ANYONE", 7, null, 201).get("token").asText();
        assertThat(openStatus(deleted)).isEqualTo(200);
        call(s.member(), delete("/api/v1/nodes/{id}", file), 204);
        assertThat(openStatus(deleted)).isEqualTo(404);
    }

    @Test
    void aPasswordIsCheckedAndGuessingIsStopped() throws Exception {
        Setup s = setup();
        UUID file = id(upload(s.member(), s.lib(), "Doc.txt", "x", false, 201));
        String token = link(s.member(), file, "ANYONE", 7, "open sesame", 201).get("token").asText();

        assertThat(code(anonymous(get("/api/v1/links/{t}", token)))).isEqualTo("password_required");
        assertThat(code(anonymous(get("/api/v1/links/{t}", token).header("X-Link-Password", "wrong")))).isEqualTo("password_wrong");
        assertThat(anonymous(get("/api/v1/links/{t}", token).header("X-Link-Password", "open sesame")).getResponse().getStatus()).isEqualTo(200);

        for (int i = 0; i < 8; i++) anonymous(get("/api/v1/links/{t}", token).header("X-Link-Password", "guess" + i));
        MvcResult locked = anonymous(get("/api/v1/links/{t}", token).header("X-Link-Password", "open sesame"));
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(code(locked)).isEqualTo("link_locked");
    }

    @Test
    void organizationLinksNeedASignIn() throws Exception {
        Setup s = setup();
        UUID file = id(upload(s.member(), s.lib(), "Doc.txt", "x", false, 201));
        String token = link(s.member(), file, "ORGANIZATION", 7, null, 201).get("token").asText();

        assertThat(code(anonymous(get("/api/v1/links/{t}", token)))).isEqualTo("sign_in_required");
        assertThat(call(s.stranger(), get("/api/v1/links/{t}", token), 200).get("name").asText()).isEqualTo("Doc.txt");
    }

    @Test
    void aFolderLinkNeverExposesWhatHasItsOwnPermissions() throws Exception {
        Setup s = setup();
        UUID folder = id(call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/folders", s.lib()), Map.of("name", "Shared")), 201));
        UUID open = id(upload(s.owner(), folder, "open.txt", "open", false, 201));
        UUID sub = id(call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/folders", folder), Map.of("name", "Sub")), 201));
        UUID deep = id(upload(s.owner(), sub, "deep.txt", "deep", false, 201));
        UUID secret = id(upload(s.owner(), folder, "secret.txt", "secret", false, 201));
        call(s.owner(), post("/api/v1/nodes/{id}/permissions/break-inheritance", secret), 200);
        UUID outside = id(upload(s.owner(), s.lib(), "outside.txt", "outside", false, 201));

        String token = link(s.owner(), folder, "ANYONE", 7, null, 201).get("token").asText();
        JsonNode listing = json.readTree(anonymous(get("/api/v1/links/{t}", token)).getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(listing.get("entries").findValuesAsText("name")).containsExactlyInAnyOrder("open.txt", "Sub");

        assertThat(anonymous(get("/api/v1/links/{t}/content", token).param("file", open.toString())).getResponse().getContentAsString()).isEqualTo("open");
        assertThat(anonymous(get("/api/v1/links/{t}/content", token).param("file", deep.toString())).getResponse().getContentAsString()).isEqualTo("deep");
        JsonNode inside = json.readTree(anonymous(get("/api/v1/links/{t}", token).param("folder", sub.toString())).getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(inside.get("entries").findValuesAsText("name")).containsExactly("deep.txt");

        for (UUID blocked : List.of(secret, outside)) {
            assertThat(anonymous(get("/api/v1/links/{t}/content", token).param("file", blocked.toString())).getResponse().getStatus()).isEqualTo(404);
        }
    }

    @Test
    void onlyEditorsShareAndExpiryIsRequired() throws Exception {
        Setup s = setup();
        UUID file = id(upload(s.member(), s.lib(), "Doc.txt", "x", false, 201));
        link(s.visitor(), file, "ANYONE", 7, null, 403);
        link(s.stranger(), file, "ANYONE", 7, null, 404);
        link(s.member(), file, "ANYONE", 0, null, 400);
        link(s.member(), file, "ANYONE", 100000, null, 400);
        link(s.owner(), s.lib(), "ANYONE", 7, null, 400); // files and folders only

        JsonNode own = link(s.member(), file, "ANYONE", 7, null, 201);
        String other = createUser("other");
        addToGroup(siteGroup(s.site(), "members"), other);
        call(other, delete("/api/v1/share-links/{id}", id(own.get("link"))), 403);
        call(s.owner(), delete("/api/v1/share-links/{id}", id(own.get("link"))), 204);
    }

    @Test
    void administratorsCanBlockLinksThatNeedNoSignIn() throws Exception {
        Setup s = setup();
        UUID file = id(upload(s.member(), s.lib(), "Doc.txt", "x", false, 201));
        String anyone = link(s.member(), file, "ANYONE", 7, null, 201).get("token").asText();
        String org = link(s.member(), file, "ORGANIZATION", 7, null, 201).get("token").asText();

        call(s.member(), jsonBody(put("/api/v1/admin/sharing"), Map.of("anyoneLinksAllowed", false)), 403);
        call(ADMIN, jsonBody(put("/api/v1/admin/sharing"), Map.of("anyoneLinksAllowed", false)), 204);
        try {
            assertThat(openStatus(anyone)).isEqualTo(404);
            link(s.member(), file, "ANYONE", 7, null, 403);
            call(s.stranger(), get("/api/v1/links/{t}", org), 200);
        } finally {
            call(ADMIN, jsonBody(put("/api/v1/admin/sharing"), Map.of("anyoneLinksAllowed", true)), 204);
        }
        assertThat(openStatus(anyone)).isEqualTo(200);

        JsonNode policy = call(ADMIN, get("/api/v1/admin/sharing"), 200);
        JsonNode row = null;
        for (JsonNode l : policy.get("links")) if (l.get("path").asText().contains("share-" + suffix)) row = l;
        assertThat(row).isNotNull();
        call(ADMIN, delete("/api/v1/admin/sharing/links/{id}", id(row)), 204);
    }

    private void share(String login, UUID node, List<UUID> people, String level, int status) throws Exception {
        call(login, jsonBody(post("/api/v1/nodes/{id}/share-people", node), Map.of("principals", people, "level", level, "message", "Please review")), status);
    }

    @Test
    void sharingWithAPersonGivesRealAccessAndTellsThem() throws Exception {
        Setup s = setup();
        String withEmail = "guest-" + suffix;
        String address = withEmail + "@example.test";
        call(ADMIN, jsonBody(post("/api/v1/admin/users"), Map.of("login", withEmail, "displayName", "Guest", "email", address, "password", PW)), 201);
        UUID guest = principalId(withEmail);
        UUID file = id(upload(s.member(), s.lib(), "Plan.txt", "the plan", false, 201));
        call(withEmail, get("/api/v1/nodes/{id}", file), 404);

        share(s.member(), file, List.of(guest), "READ", 204);

        assertThat(download(withEmail, file)).isEqualTo("the plan");
        JsonNode shared = call(withEmail, get("/api/v1/me/shared-with-me"), 200);
        assertThat(shared.findValuesAsText("name")).containsExactly("Plan.txt");
        assertThat(shared.get(0).get("sharedBy").get("displayName").asText()).isEqualTo("member");
        // everyone who could read it before still can
        assertThat(download(s.visitor(), file)).isEqualTo("the plan");

        assertThat(outbox.findByRecipient(address)).hasSize(1);
        assertThat(outbox.findByRecipient(address).get(0).getSubject()).contains("member shared Plan.txt with you");
        assertThat(outbox.findByRecipient(address).get(0).getBodyText()).contains("Please review").contains("https://nexora.test/sites/share-");

        JsonNode grants = call(s.member(), get("/api/v1/nodes/{id}/shares", file), 200).get("people");
        call(s.member(), delete("/api/v1/share-grants/{id}", id(grants.get(0))), 204);
        call(withEmail, get("/api/v1/nodes/{id}", file), 404);
        assertThat(call(withEmail, get("/api/v1/me/shared-with-me"), 200)).isEmpty();
    }

    @Test
    void sharingCannotGiveTooMuchOrTakeAwayAccess() throws Exception {
        Setup s = setup();
        UUID file = id(upload(s.member(), s.lib(), "Plan.txt", "x", false, 201));
        UUID stranger = principalId(s.stranger());
        share(s.member(), file, List.of(stranger), "EDIT", 403);
        share(s.member(), file, List.of(stranger), "FULL_CONTROL", 403);
        share(s.visitor(), file, List.of(stranger), "READ", 403);

        // the owners' group keeps Full Control even when someone "shares" Read with it
        share(s.member(), file, List.of(siteGroup(s.site(), "owners")), "READ", 204);
        call(s.owner(), jsonBody(patch("/api/v1/nodes/{id}", file), Map.of("title", "Still editable")), 200);
        call(s.owner(), get("/api/v1/nodes/{id}/permissions", file), 200);
    }

    @Test
    void recentShowsWhatYouOpenedAndHidesWhatYouLost() throws Exception {
        Setup s = setup();
        UUID first = id(upload(s.member(), s.lib(), "First.txt", "1", false, 201));
        UUID second = id(upload(s.owner(), s.lib(), "Second.txt", "2", false, 201));
        assertThat(call(s.visitor(), get("/api/v1/me/recent"), 200)).isEmpty();

        call(s.visitor(), post("/api/v1/nodes/{id}/viewed", second), 204);
        download(s.visitor(), first);
        JsonNode recent = call(s.visitor(), get("/api/v1/me/recent"), 200);
        assertThat(recent.findValuesAsText("name")).containsExactly("First.txt", "Second.txt");

        call(s.owner(), delete("/api/v1/nodes/{id}", first), 204);
        assertThat(call(s.visitor(), get("/api/v1/me/recent"), 200).findValuesAsText("name")).containsExactly("Second.txt");
    }
}
