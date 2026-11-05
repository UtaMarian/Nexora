package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.alerts.AlertService;
import io.nexora.mail.OutboxMail;
import io.nexora.mail.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** "Alert me": who hears about what, and when. */
class AlertsTest extends ApiTestSupport {
    @Autowired AlertService alerts;
    @Autowired OutboxRepository outbox;

    private record Setup(UUID site, UUID lib, String owner, String reader, String address) {}

    private Setup setup() throws Exception {
        String owner = createUser("owner");
        String reader = "reader-" + suffix;
        String address = reader + "@example.test";
        call(ADMIN, jsonBody(post("/api/v1/admin/users"), Map.of("login", reader, "displayName", "Reader", "email", address, "password", PW)), 201);
        UUID site = id(createSite(owner, "alerts-" + suffix));
        addToGroup(siteGroup(site, "visitors"), reader);
        return new Setup(site, defaultLibrary(owner, site), owner, reader, address);
    }

    private void process() {
        alerts.match(Instant.now().plusSeconds(5));
    }

    private List<OutboxMail> mail(Setup s) {
        return outbox.findByRecipient(s.address());
    }

    private void subscribe(String login, UUID node, String frequency) throws Exception {
        call(login, jsonBody(put("/api/v1/nodes/{id}/alert", node), Map.of("frequency", frequency)), 200);
    }

    @Test
    void emailsAboutChangesBelowTheAlert() throws Exception {
        Setup s = setup();
        subscribe(s.reader(), s.lib(), "IMMEDIATE");
        UUID folder = id(call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/folders", s.lib()), Map.of("name", "Reports")), 201));
        upload(s.owner(), folder, "Q3.txt", "x", false, 201);
        process();

        assertThat(mail(s)).extracting(OutboxMail::getSubject).contains("[NEXORA] owner added Q3.txt", "[NEXORA] owner added Reports");
        assertThat(mail(s).get(0).getBodyHtml()).contains("https://nexora.test/sites/alerts-");
    }

    @Test
    void repeatedSavesBecomeOneEmailAndYourOwnChangesAreSilent() throws Exception {
        Setup s = setup();
        subscribe(s.reader(), s.lib(), "IMMEDIATE");
        subscribe(s.owner(), s.lib(), "IMMEDIATE");
        UUID file = id(upload(s.owner(), s.lib(), "Doc.txt", "1", false, 201));
        process();
        int before = mail(s).size();
        for (int i = 2; i < 6; i++) upload(s.owner(), s.lib(), "Doc.txt", String.valueOf(i), true, 201);
        process();
        assertThat(mail(s).size() - before).isEqualTo(1);
        assertThat(mail(s).get(mail(s).size() - 1).getSubject()).contains("changed Doc.txt");
        assertThat(file).isNotNull();
    }

    @Test
    void someoneWhoLostAccessHearsNothing() throws Exception {
        Setup s = setup();
        UUID folder = id(call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/folders", s.lib()), Map.of("name", "Private")), 201));
        subscribe(s.reader(), folder, "IMMEDIATE");
        call(s.owner(), post("/api/v1/nodes/{id}/permissions/break-inheritance", folder), 200);
        call(s.owner(), delete("/api/v1/nodes/{id}/permissions/{p}", folder, siteGroup(s.site(), "visitors")), 200);
        upload(s.owner(), folder, "Salaries.txt", "x", false, 201);
        process();
        assertThat(mail(s)).isEmpty();
    }

    @Test
    void anAlertNeedsAccessAndCanBeRemoved() throws Exception {
        Setup s = setup();
        String stranger = createUser("stranger");
        call(stranger, jsonBody(put("/api/v1/nodes/{id}/alert", s.lib()), Map.of("frequency", "IMMEDIATE")), 404);

        subscribe(s.reader(), s.lib(), "DAILY");
        JsonNode mine = call(s.reader(), get("/api/v1/me/alerts"), 200);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("frequency").asText()).isEqualTo("DAILY");
        assertThat(call(s.reader(), get("/api/v1/nodes/{id}/alert", s.lib()), 200).get("frequency").asText()).isEqualTo("DAILY");

        call(stranger, delete("/api/v1/me/alerts/{id}", id(mine.get(0))), 404);
        call(s.reader(), delete("/api/v1/me/alerts/{id}", id(mine.get(0))), 204);
        upload(s.owner(), s.lib(), "After.txt", "x", false, 201);
        process();
        assertThat(mail(s)).isEmpty();
    }

    @Test
    void dailyAlertsCollectIntoOneSummary() throws Exception {
        Setup s = setup();
        subscribe(s.reader(), s.lib(), "DAILY");
        upload(s.owner(), s.lib(), "One.txt", "1", false, 201);
        upload(s.owner(), s.lib(), "Two.txt", "2", false, 201);
        process();
        assertThat(mail(s)).isEmpty();

        alerts.summarise(Instant.now());
        assertThat(mail(s)).isEmpty(); // not a day yet

        alerts.summarise(Instant.now().plus(Duration.ofDays(1)).plusSeconds(60));
        assertThat(mail(s)).hasSize(1);
        assertThat(mail(s).get(0).getSubject()).contains("2 changes in");
        assertThat(mail(s).get(0).getBodyText()).contains("owner added One.txt").contains("owner added Two.txt");

        alerts.summarise(Instant.now().plus(Duration.ofDays(3)));
        assertThat(mail(s)).hasSize(1); // nothing new, nothing sent
    }

    @Test
    void administratorsSeeEmailInHealth() throws Exception {
        assertThat(call(ADMIN, get("/api/v1/admin/health"), 200).findValuesAsText("component")).contains("Email");
    }
}
