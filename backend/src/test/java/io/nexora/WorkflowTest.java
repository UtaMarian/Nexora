package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.identity.CurrentUser;
import io.nexora.mail.OutboxMail;
import io.nexora.mail.OutboxRepository;
import io.nexora.workflow.ApprovalService;
import io.nexora.workflow.WorkflowEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Approvals, reviews and rules, including what must never happen. */
class WorkflowTest extends ApiTestSupport {
    @Autowired WorkflowEngine engine;
    @Autowired OutboxRepository outbox;
    @Autowired ApprovalService approvals;

    private record Setup(UUID site, UUID lib, String owner, String author, String boss, String peer, String visitor, String url) {}

    private String userWithEmail(String base) throws Exception {
        String login = base + "-" + suffix;
        call(ADMIN, jsonBody(post("/api/v1/admin/users"), Map.of("login", login, "displayName", base, "email", login + "@example.test", "password", PW)), 201);
        return login;
    }

    private Setup setup() throws Exception {
        String owner = createUser("owner");
        String author = userWithEmail("author");
        String boss = userWithEmail("boss");
        String peer = userWithEmail("peer");
        String visitor = userWithEmail("visitor");
        String url = "flow-" + suffix;
        UUID site = id(createSite(owner, url));
        for (String m : List.of(author, boss, peer)) addToGroup(siteGroup(site, "members"), m);
        addToGroup(siteGroup(site, "visitors"), visitor);
        return new Setup(site, defaultLibrary(owner, site), owner, author, boss, peer, visitor, url);
    }

    private JsonNode request(String login, UUID node, String kind, String mode, List<String> people, int status) throws Exception {
        List<String> ids = new ArrayList<>();
        for (String p : people) ids.add(principalId(p).toString());
        Map<String, Object> body = new HashMap<>(Map.of("people", ids, "kind", kind));
        if (mode != null) body.put("mode", mode);
        return call(login, jsonBody(post("/api/v1/nodes/{id}/approvals", node), body), status);
    }

    private JsonNode decide(String login, JsonNode approval, boolean approve, String comment, int status) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("approve", approve));
        if (comment != null) body.put("comment", comment);
        return call(login, jsonBody(post("/api/v1/approvals/{id}/decide", id(approval)), body), status);
    }

    private JsonNode approval(String login, JsonNode a) throws Exception {
        return call(login, get("/api/v1/approvals/{id}", id(a)), 200);
    }

    private JsonNode node(String login, UUID id) throws Exception {
        return call(login, get("/api/v1/nodes/{id}", id), 200).get("node");
    }

    private List<String> subjects(String login) {
        return outbox.findByRecipient(login + "@example.test").stream().map(OutboxMail::getSubject).toList();
    }

    // ---- approvals

    @Test
    void anApprovalAsksAndAnswers() throws Exception {
        Setup s = setup();
        UUID file = id(upload(s.author(), s.lib(), "Plan.txt", "v1", false, 201));
        JsonNode a = request(s.author(), file, "APPROVAL", "ALL", List.of(s.boss()), 201);
        assertThat(a.get("status").asText()).isEqualTo("PENDING");
        assertThat(a.get("versionLabel").asText()).isEqualTo("1.0");
        assertThat(subjects(s.boss())).anyMatch(x -> x.contains("Approval needed: Plan.txt"));
        assertThat(node(s.author(), file).get("approvalStatus").asText()).isEqualTo("APPROVAL:PENDING");

        JsonNode waiting = call(s.boss(), get("/api/v1/me/approvals"), 200);
        assertThat(waiting.findValuesAsText("itemName")).containsExactly("Plan.txt");
        assertThat(waiting.get(0).get("canDecide").asBoolean()).isTrue();
        assertThat(call(s.boss(), get("/api/v1/me/approvals/count"), 200).get("waiting").asInt()).isEqualTo(1);

        JsonNode done = decide(s.boss(), a, true, "Looks right", 200);
        assertThat(done.get("status").asText()).isEqualTo("APPROVED");
        assertThat(done.get("outcome").asText()).contains("boss");
        assertThat(subjects(s.author())).anyMatch(x -> x.contains("Approved: Plan.txt"));
        assertThat(call(s.boss(), get("/api/v1/me/approvals"), 200)).isEmpty();
        assertThat(call(s.author(), get("/api/v1/me/approvals").param("box", "sent"), 200)).hasSize(1);
    }

    @Test
    void approvalPublishesADraft() throws Exception {
        Setup s = setup();
        call(s.owner(), jsonBody(put("/api/v1/nodes/{id}/settings", s.lib()), Map.of("versioningEnabled", true, "minorVersionsEnabled", true, "requireCheckout", false, "maxVersions", 50)), 200);
        UUID file = id(upload(s.author(), s.lib(), "Policy.txt", "draft", false, 201));
        assertThat(node(s.author(), file).get("version").asText()).isEqualTo("0.1");
        JsonNode a = request(s.author(), file, "APPROVAL", "ANY", List.of(s.boss()), 201);
        JsonNode done = decide(s.boss(), a, true, null, 200);
        assertThat(done.get("outcome").asText()).contains("published as version 1.0");
        assertThat(node(s.author(), file).get("version").asText()).isEqualTo("1.0");
        engine.drain();
        // publishing it is the workflow's own doing, so the finished approval stands
        assertThat(approval(s.author(), a).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void modesDecideWhenItIsOver() throws Exception {
        Setup s = setup();
        UUID any = id(upload(s.author(), s.lib(), "any.txt", "1", false, 201));
        JsonNode a = request(s.author(), any, "APPROVAL", "ANY", List.of(s.boss(), s.peer()), 201);
        assertThat(decide(s.peer(), a, true, null, 200).get("status").asText()).isEqualTo("APPROVED");
        assertThat(approval(s.boss(), a).get("steps").findValuesAsText("decision")).contains("SKIPPED");
        decide(s.boss(), a, true, null, 409);

        UUID all = id(upload(s.author(), s.lib(), "all.txt", "1", false, 201));
        JsonNode b = request(s.author(), all, "APPROVAL", "ALL", List.of(s.boss(), s.peer()), 201);
        assertThat(decide(s.boss(), b, true, null, 200).get("status").asText()).isEqualTo("PENDING");
        assertThat(decide(s.peer(), b, false, "Wrong figures", 200).get("status").asText()).isEqualTo("REJECTED");

        UUID seq = id(upload(s.author(), s.lib(), "seq.txt", "1", false, 201));
        JsonNode c = request(s.author(), seq, "APPROVAL", "SEQUENTIAL", List.of(s.boss(), s.peer()), 201);
        assertThat(subjects(s.peer()).stream().filter(x -> x.contains("seq.txt"))).isEmpty();
        decide(s.peer(), c, true, null, 403); // not their turn yet
        decide(s.boss(), c, true, null, 200);
        assertThat(subjects(s.peer())).anyMatch(x -> x.contains("Approval needed: seq.txt"));
        assertThat(decide(s.peer(), c, true, null, 200).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void aReviewCollectsEveryoneAndNeverRejects() throws Exception {
        Setup s = setup();
        UUID file = id(upload(s.author(), s.lib(), "Draft.txt", "1", false, 201));
        JsonNode a = request(s.author(), file, "REVIEW", null, List.of(s.boss(), s.peer()), 201);
        assertThat(a.get("mode").asText()).isEqualTo("ALL");
        assertThat(decide(s.boss(), a, false, "Section 2 is wrong", 200).get("status").asText()).isEqualTo("PENDING");
        JsonNode done = decide(s.peer(), a, true, "Fine by me", 200);
        assertThat(done.get("status").asText()).isEqualTo("CHANGES_REQUESTED");
        assertThat(done.get("steps").findValuesAsText("comment")).contains("Section 2 is wrong", "Fine by me");
        assertThat(subjects(s.author())).anyMatch(x -> x.contains("Changes requested"));
    }

    @Test
    void twoAnswersAtTheSameMomentStillFinishTheApproval() throws Exception {
        Setup s = setup();
        CurrentUser boss = current(s.boss());
        CurrentUser peer = current(s.peer());
        for (int round = 0; round < 15; round++) {
            UUID file = id(upload(s.author(), s.lib(), "Race" + round + ".txt", "1", false, 201));
            JsonNode a = request(s.author(), file, "APPROVAL", "ALL", List.of(s.boss(), s.peer()), 201);
            UUID approval = id(a);
            CyclicBarrier gate = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<?>> answers = new ArrayList<>();
            for (CurrentUser who : List.of(boss, peer)) {
                answers.add(pool.submit(() -> {
                    gate.await();
                    return approvals.decide(who, approval, true, null);
                }));
            }
            for (Future<?> answer : answers) answer.get();
            pool.shutdown();
            assertThat(approval(s.author(), a).get("status").asText()).as("round " + round).isEqualTo("APPROVED");
        }
    }

    private CurrentUser current(String login) throws Exception {
        return new CurrentUser(principalId(login), login, login, false);
    }

    @Test
    void youCannotApproveYourOwnWorkOrCountTwice() throws Exception {
        Setup s = setup();
        UUID file = id(upload(s.author(), s.lib(), "Mine.txt", "1", false, 201));
        request(s.author(), file, "APPROVAL", "ALL", List.of(s.author()), 400);

        // a group the requester belongs to: someone else in it must answer
        JsonNode a = request(s.author(), file, "APPROVAL", "ALL", List.of(siteGroupLogin(s, "members")), 201);
        decide(s.author(), a, true, null, 403);
        assertThat(call(s.author(), get("/api/v1/me/approvals"), 200)).isEmpty();
        assertThat(decide(s.peer(), a, true, null, 200).get("status").asText()).isEqualTo("APPROVED");

        // one person in two groups still counts once
        UUID twice = id(upload(s.author(), s.lib(), "Twice.txt", "1", false, 201));
        JsonNode b = request(s.author(), twice, "APPROVAL", "ALL", List.of(s.boss(), siteGroupLogin(s, "members")), 201);
        decide(s.boss(), b, true, null, 200);
        decide(s.boss(), b, true, null, 403);
        assertThat(decide(s.peer(), b, true, null, 200).get("status").asText()).isEqualTo("APPROVED");
    }

    private String siteGroupLogin(Setup s, String role) {
        return s.url() + "-" + role;
    }

    @Test
    void onlyTheRightPeopleTakePart() throws Exception {
        Setup s = setup();
        String stranger = userWithEmail("stranger");
        UUID file = id(upload(s.author(), s.lib(), "Doc.txt", "1", false, 201));

        request(s.visitor(), file, "APPROVAL", "ALL", List.of(s.boss()), 403);
        request(s.author(), file, "APPROVAL", "ALL", List.of(stranger), 400); // cannot open it
        request(s.author(), s.lib(), "APPROVAL", "ALL", List.of(s.boss()), 400); // not a file or item

        JsonNode a = request(s.author(), file, "APPROVAL", "ALL", List.of(s.boss()), 201);
        request(s.author(), file, "APPROVAL", "ALL", List.of(s.peer()), 409); // one at a time
        call(stranger, get("/api/v1/approvals/{id}", id(a)), 404);
        decide(stranger, a, true, null, 404);
        decide(s.peer(), a, true, null, 403);
        decide(s.boss(), a, false, null, 400); // rejecting needs a reason

        call(s.peer(), post("/api/v1/approvals/{id}/cancel", id(a)), 403);
        assertThat(call(s.author(), post("/api/v1/approvals/{id}/cancel", id(a)), 200).get("status").asText()).isEqualTo("CANCELLED");
        decide(s.boss(), a, true, null, 409);
    }

    @Test
    void anApproverCanPassItOn() throws Exception {
        Setup s = setup();
        UUID file = id(upload(s.author(), s.lib(), "Doc.txt", "1", false, 201));
        JsonNode a = request(s.author(), file, "APPROVAL", "ALL", List.of(s.boss()), 201);
        call(s.boss(), jsonBody(post("/api/v1/approvals/{id}/reassign", id(a)), Map.of("principalId", principalId(s.author()))), 400);
        call(s.visitor(), jsonBody(post("/api/v1/approvals/{id}/reassign", id(a)), Map.of("principalId", principalId(s.peer()))), 403);
        call(s.boss(), jsonBody(post("/api/v1/approvals/{id}/reassign", id(a)), Map.of("principalId", principalId(s.peer()))), 200);
        decide(s.boss(), a, true, null, 403);
        assertThat(decide(s.peer(), a, true, null, 200).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void changingTheContentCancelsTheApproval() throws Exception {
        Setup s = setup();
        UUID file = id(upload(s.author(), s.lib(), "Doc.txt", "what they saw", false, 201));
        JsonNode a = request(s.author(), file, "APPROVAL", "ALL", List.of(s.boss()), 201);
        upload(s.author(), s.lib(), "Doc.txt", "something else", true, 201);

        // the worker has not run, and it still cannot be approved
        decide(s.boss(), a, true, null, 409);
        engine.drain();
        assertThat(approval(s.author(), a).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(approval(s.author(), a).get("outcome").asText()).contains("changed");
        assertThat(subjects(s.author())).anyMatch(x -> x.contains("Request again"));

        // a change to columns alone does not touch the file that was approved
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/columns", s.lib()), Map.of("displayName", "Dept", "type", "TEXT")), 201);
        JsonNode b = request(s.author(), file, "APPROVAL", "ALL", List.of(s.boss()), 201);
        call(s.author(), jsonBody(patch("/api/v1/nodes/{id}", file), Map.of("metadata", Map.of("Dept", "Finance"))), 200);
        engine.drain();
        assertThat(decide(s.boss(), b, true, null, 200).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void listItemsGoThroughApprovalToo() throws Exception {
        Setup s = setup();
        UUID list = id(call(s.owner(), jsonBody(post("/api/v1/sites/{id}/lists", s.site()), Map.of("name", "Requests", "template", "CUSTOM")), 201));
        UUID item = id(call(s.author(), jsonBody(post("/api/v1/nodes/{id}/items", list), Map.of("title", "New laptop")), 201));
        JsonNode a = request(s.author(), item, "APPROVAL", "ALL", List.of(s.boss()), 201);
        call(s.author(), jsonBody(patch("/api/v1/nodes/{id}", item), Map.of("title", "Two new laptops")), 200);
        engine.drain();
        assertThat(approval(s.author(), a).get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void anApproverWhoLosesAccessCanNoLongerAnswer() throws Exception {
        Setup s = setup();
        UUID folder = id(call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/folders", s.lib()), Map.of("name", "Private")), 201));
        UUID file = id(upload(s.owner(), folder, "Doc.txt", "1", false, 201));
        JsonNode a = request(s.owner(), file, "APPROVAL", "ALL", List.of(s.boss()), 201);
        call(s.owner(), post("/api/v1/nodes/{id}/permissions/break-inheritance", folder), 200);
        call(s.owner(), delete("/api/v1/nodes/{id}/permissions/{p}", folder, siteGroup(s.site(), "members")), 200);
        decide(s.boss(), a, true, null, 404);
        assertThat(call(s.boss(), get("/api/v1/me/approvals"), 200)).isEmpty();
    }

    // ---- rules

    private Map<String, Object> definition(String trigger, List<Map<String, Object>> conditions, List<Map<String, Object>> actions) {
        return new HashMap<>(Map.of("trigger", trigger, "conditions", conditions, "actions", actions));
    }

    private static Map<String, Object> person(String login) {
        return Map.of("kind", "PERSON", "login", login);
    }

    private JsonNode rule(String login, UUID container, Map<String, Object> definition, int status) throws Exception {
        return call(login, jsonBody(post("/api/v1/nodes/{id}/rules", container), Map.of("name", "Rule " + UUID.randomUUID(), "definition", definition)), status);
    }

    private static Map<String, Object> email(String subject, Map<String, Object> who) {
        return Map.of("type", "SEND_EMAIL", "subject", subject, "body", "See {link}", "people", List.of(who));
    }

    @Test
    void aRuleEmailsWhenItsConditionsHold() throws Exception {
        Setup s = setup();
        rule(s.owner(), s.lib(), definition("CREATED",
                List.of(Map.of("column", "FileType", "operator", "eq", "value", "txt")),
                List.of(email("{by} added {title}", person(s.boss())))), 201);

        upload(s.author(), s.lib(), "Yes.txt", "1", false, 201);
        upload(s.author(), s.lib(), "No.pdf", "1", false, 201);
        engine.drain();

        assertThat(subjects(s.boss())).anyMatch(x -> x.contains("author added Yes.txt")).noneMatch(x -> x.contains("No.pdf"));
        JsonNode runs = call(s.owner(), get("/api/v1/nodes/{id}/rule-runs", s.lib()), 200);
        assertThat(runs.findValuesAsText("status")).contains("DONE", "SKIPPED");
    }

    @Test
    void aRuleStartsAnApprovalAndAnotherFilesTheResult() throws Exception {
        Setup s = setup();
        rule(s.owner(), s.lib(), definition("CREATED", List.of(), List.of(new HashMap<>(Map.of("type", "REQUEST_APPROVAL", "people", List.of(person(s.boss())), "mode", "ALL", "dueDays", 3)))), 201);
        rule(s.owner(), s.lib(), definition("APPROVED", List.of(), List.of(Map.of("type", "MOVE_TO_FOLDER", "folder", "Approved/2026"))), 201);

        UUID file = id(upload(s.author(), s.lib(), "Invoice.txt", "1", false, 201));
        engine.drain();
        JsonNode waiting = call(s.boss(), get("/api/v1/me/approvals"), 200);
        assertThat(waiting.findValuesAsText("itemName")).containsExactly("Invoice.txt");
        assertThat(waiting.get(0).get("requestedBy").asText()).isEqualTo("author");
        assertThat(waiting.get(0).get("dueAt")).isNotNull();

        decide(s.boss(), waiting.get(0), true, null, 200);
        engine.drain();
        assertThat(node(s.author(), file).get("path").asText()).isEqualTo("/sites/" + s.url() + "/Shared Documents/Approved/2026/Invoice.txt");
    }

    @Test
    void rulesCannotLoop() throws Exception {
        Setup s = setup();
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/columns", s.lib()), Map.of("displayName", "Stamp", "type", "TEXT")), 201);
        rule(s.owner(), s.lib(), definition("CHANGED", List.of(), List.of(Map.of("type", "SET_VALUE", "column", "Stamp", "value", "Seen"))), 201);
        rule(s.owner(), s.lib(), definition("CREATED", List.of(), List.of(Map.of("type", "SET_VALUE", "column", "Stamp", "value", "New"))), 201);

        UUID file = id(upload(s.author(), s.lib(), "Doc.txt", "1", false, 201));
        engine.drain();
        assertThat(node(s.author(), file).get("metadata").get("Stamp").asText()).isEqualTo("New");
        assertThat(node(s.author(), file).get("modifiedBy").get("displayName").asText()).isEqualTo("Workflow");

        upload(s.author(), s.lib(), "Doc.txt", "2", true, 201);
        engine.drain();
        engine.drain();
        JsonNode runs = call(s.owner(), get("/api/v1/nodes/{id}/rule-runs", s.lib()), 200);
        assertThat(runs).hasSize(2); // one for each person-made change, and none for the rules' own
    }

    @Test
    void anEmailNeverReachesSomeoneWhoCannotSeeTheItem() throws Exception {
        Setup s = setup();
        String stranger = userWithEmail("stranger");
        rule(s.owner(), s.lib(), definition("CREATED", List.of(), List.of(email("Secret {title}", person(stranger)))), 201);
        upload(s.author(), s.lib(), "Salaries.txt", "1", false, 201);
        engine.drain();
        assertThat(subjects(stranger)).isEmpty();
    }

    @Test
    void aFailingRuleIsLoggedAndHarmsNothing() throws Exception {
        Setup s = setup();
        rule(s.owner(), s.lib(), definition("CREATED", List.of(), List.of(
                Map.of("type", "SET_VALUE", "column", "Choice", "value", "x"))), 400); // no such column
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/columns", s.lib()), Map.of("displayName", "Size", "type", "NUMBER")), 201);
        rule(s.owner(), s.lib(), definition("CREATED", List.of(), List.of(
                email("First", person(s.boss())),
                Map.of("type", "SET_VALUE", "column", "Size", "value", "not a number"))), 201);
        rule(s.owner(), s.lib(), definition("CREATED", List.of(), List.of(email("Second rule ran", person(s.peer())))), 201);

        upload(s.author(), s.lib(), "Doc.txt", "1", false, 201);
        engine.drain();
        JsonNode runs = call(s.owner(), get("/api/v1/nodes/{id}/rule-runs", s.lib()), 200);
        assertThat(runs.findValuesAsText("status")).contains("FAILED", "DONE");
        // the failed rule undid its own email, and the next rule still ran
        assertThat(subjects(s.boss())).isEmpty();
        assertThat(subjects(s.peer())).anyMatch(x -> x.contains("Second rule ran"));
    }

    @Test
    void dateRulesFireOncePerDate() throws Exception {
        Setup s = setup();
        UUID list = id(call(s.owner(), jsonBody(post("/api/v1/sites/{id}/lists", s.site()), Map.of("name", "Contracts", "template", "CUSTOM")), 201));
        String column = call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/columns", list), Map.of("displayName", "Renewal", "type", "DATE")), 201).get("internalName").asText();
        Map<String, Object> d = definition("DATE", List.of(), List.of(email("Renew {title}", person(s.boss()))));
        d.put("dateColumn", column);
        d.put("daysBefore", 7);
        rule(s.owner(), list, d, 201);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        call(s.author(), jsonBody(post("/api/v1/nodes/{id}/items", list), Map.of("title", "Soon", "values", Map.of(column, today.plusDays(7).toString()))), 201);
        call(s.author(), jsonBody(post("/api/v1/nodes/{id}/items", list), Map.of("title", "Later", "values", Map.of(column, today.plusDays(30).toString()))), 201);
        engine.sweepDates(today);
        engine.sweepDates(today);
        assertThat(subjects(s.boss()).stream().filter(x -> x.contains("Renew Soon"))).hasSize(1);
        assertThat(subjects(s.boss())).noneMatch(x -> x.contains("Later"));
    }

    @Test
    void aRuleCanAddToAListAndAttachmentsDoNotTriggerIt() throws Exception {
        Setup s = setup();
        UUID requests = id(call(s.owner(), jsonBody(post("/api/v1/sites/{id}/lists", s.site()), Map.of("name", "Requests", "template", "CUSTOM")), 201));
        UUID log = id(call(s.owner(), jsonBody(post("/api/v1/sites/{id}/lists", s.site()), Map.of("name", "Log", "template", "CUSTOM")), 201));
        rule(s.owner(), requests, definition("CREATED", List.of(), List.of(Map.of("type", "CREATE_ITEM", "list", "Log", "title", "New: {title}"))), 201);

        UUID item = id(call(s.author(), jsonBody(post("/api/v1/nodes/{id}/items", requests), Map.of("title", "Chair")), 201));
        upload(s.author(), item, "photo.txt", "x", false, 201);
        engine.drain();
        JsonNode rows = call(s.owner(), get("/api/v1/nodes/{id}/children", log), 200);
        assertThat(rows.findValuesAsText("title")).containsExactly("New: Chair");
    }

    @Test
    void onlyManagersWriteRulesAndRulesStayInBounds() throws Exception {
        Setup s = setup();
        Map<String, Object> ok = definition("CREATED", List.of(), List.of(email("Hello", person(s.boss()))));
        rule(s.visitor(), s.lib(), ok, 403);
        rule(s.owner(), s.site(), ok, 400);
        rule(s.owner(), s.lib(), definition("SOMETIMES", List.of(), List.of(email("x", person(s.boss())))), 400);
        rule(s.owner(), s.lib(), definition("CREATED", List.of(), List.of()), 400);
        rule(s.owner(), s.lib(), definition("CREATED", List.of(), List.of(email("x", person("nobody-at-all")))), 400);
        rule(s.owner(), s.lib(), definition("CREATED", List.of(), List.of(Map.of("type", "MOVE_TO_FOLDER", "folder", "../../other"))), 400);
        rule(s.owner(), s.lib(), definition("CREATED", List.of(), List.of(Map.of("type", "CREATE_ITEM", "list", "../other-site/List", "title", "x"))), 400);
        rule(s.owner(), s.lib(), definition("DATE", List.of(), List.of(email("x", person(s.boss())))), 400);

        JsonNode saved = rule(s.owner(), s.lib(), ok, 201);
        call(s.visitor(), jsonBody(put("/api/v1/rules/{id}", id(saved)), Map.of("name", "Mine", "definition", ok)), 403);
        call(s.visitor(), delete("/api/v1/rules/{id}", id(saved)), 403);
        call(s.owner(), jsonBody(put("/api/v1/rules/{id}", id(saved)), Map.of("name", "Paused", "enabled", false, "definition", ok)), 200);
        upload(s.author(), s.lib(), "Doc.txt", "1", false, 201);
        engine.drain();
        assertThat(subjects(s.boss())).isEmpty();
        call(s.owner(), delete("/api/v1/rules/{id}", id(saved)), 204);
        assertThat(call(s.owner(), get("/api/v1/nodes/{id}/rules", s.lib()), 200)).isEmpty();
    }
}
