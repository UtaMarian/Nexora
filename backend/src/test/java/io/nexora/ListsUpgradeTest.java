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

/** Saved views, lookup columns, attachments and item history. */
class ListsUpgradeTest extends ApiTestSupport {
    private record Setup(UUID site, UUID list, String owner, String member, String visitor) {}

    private Setup setup() throws Exception {
        String owner = createUser("owner");
        String member = createUser("member");
        String visitor = createUser("visitor");
        UUID site = id(createSite(owner, "lists-" + suffix));
        addToGroup(siteGroup(site, "members"), member);
        addToGroup(siteGroup(site, "visitors"), visitor);
        UUID list = list(owner, site, "Tasks", "TASKS");
        return new Setup(site, list, owner, member, visitor);
    }

    private UUID list(String login, UUID site, String name, String template) throws Exception {
        return id(call(login, jsonBody(post("/api/v1/sites/{id}/lists", site), Map.of("name", name, "template", template)), 201));
    }

    private UUID item(String login, UUID list, String title, Map<String, Object> values) throws Exception {
        return id(call(login, jsonBody(post("/api/v1/nodes/{id}/items", list), Map.of("title", title, "values", values)), 201));
    }

    private String internal(String login, UUID list, String displayName) throws Exception {
        for (JsonNode c : call(login, get("/api/v1/nodes/{id}/columns", list), 200)) {
            if (c.get("displayName").asText().equals(displayName)) return c.get("internalName").asText();
        }
        throw new AssertionError("No column " + displayName);
    }

    private JsonNode addLookup(String login, UUID list, UUID target, int status) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("displayName", "Department", "type", "LOOKUP"));
        body.put("lookupListId", target);
        return call(login, jsonBody(post("/api/v1/nodes/{id}/columns", list), body), status);
    }

    private JsonNode node(String login, UUID id) throws Exception {
        return call(login, get("/api/v1/nodes/{id}", id), 200).get("node");
    }

    // ---- views

    private static Map<String, Object> view(String name, String type) {
        Map<String, Object> v = new HashMap<>(Map.of("name", name, "type", type));
        v.put("columns", List.of("Title"));
        return v;
    }

    @Test
    void savesViewsForEveryoneToUse() throws Exception {
        Setup s = setup();
        String status = internal(s.owner(), s.list(), "Status");
        Map<String, Object> open = view("Open work", "table");
        open.put("columns", List.of("Title", status, "Modified"));
        open.put("sortBy", status);
        open.put("sortDescending", true);
        open.put("filters", List.of(Map.of("column", status, "operator", "ne", "value", "Completed")));
        open.put("isDefault", true);

        JsonNode saved = call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/views", s.list()), open), 201);
        String viewId = saved.get("id").asText();

        JsonNode seen = call(s.visitor(), get("/api/v1/nodes/{id}/views", s.list()), 200);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).get("filters").get(0).get("operator").asText()).isEqualTo("ne");

        // a new default replaces the old one
        Map<String, Object> second = view("Everything", "table");
        second.put("isDefault", true);
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/views", s.list()), second), 201);
        JsonNode both = call(s.owner(), get("/api/v1/nodes/{id}/views", s.list()), 200);
        assertThat(both.findValues("isDefault").stream().filter(JsonNode::asBoolean).count()).isEqualTo(1);

        open.put("name", "Still open");
        assertThat(call(s.owner(), jsonBody(put("/api/v1/nodes/{id}/views/{v}", s.list(), viewId), open), 200).get("name").asText()).isEqualTo("Still open");
        call(s.owner(), delete("/api/v1/nodes/{id}/views/{v}", s.list(), viewId), 204);
        assertThat(call(s.owner(), get("/api/v1/nodes/{id}/views", s.list()), 200)).hasSize(1);
    }

    @Test
    void rejectsBadViews() throws Exception {
        Setup s = setup();
        call(s.visitor(), jsonBody(post("/api/v1/nodes/{id}/views", s.list()), view("Mine", "table")), 403);
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/views", s.list()), view("", "table")), 400);
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/views", s.list()), view("Odd", "timeline")), 400);

        Map<String, Object> unknown = view("Ghost", "table");
        unknown.put("columns", List.of("Nope"));
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/views", s.list()), unknown), 400);

        Map<String, Object> calendar = view("Dates", "calendar");
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/views", s.list()), calendar), 400);
        calendar.put("dateColumn", internal(s.owner(), s.list(), "Status"));
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/views", s.list()), calendar), 400);
        calendar.put("dateColumn", internal(s.owner(), s.list(), "Due date"));
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/views", s.list()), calendar), 201);
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/views", s.list()), calendar), 409);
    }

    // ---- lookups

    @Test
    void lookupsPickFromAnotherListAndFollowRenames() throws Exception {
        Setup s = setup();
        UUID departments = list(s.owner(), s.site(), "Departments", "CUSTOM");
        UUID finance = item(s.owner(), departments, "Finance", Map.of());
        String col = addLookup(s.owner(), s.list(), departments, 201).get("internalName").asText();
        String number = node(s.owner(), finance).get("name").asText();

        UUID task = item(s.member(), s.list(), "Close the books", Map.of(internal(s.owner(), s.list(), "Status"), "In progress", col, number));
        assertThat(node(s.member(), task).get("metadata").get(col).get("title").asText()).isEqualTo("Finance");

        call(s.owner(), jsonBody(patch("/api/v1/nodes/{id}", finance), Map.of("title", "Finance and Tax")), 200);
        assertThat(node(s.member(), task).get("metadata").get(col).get("title").asText()).isEqualTo("Finance and Tax");

        call(s.member(), jsonBody(patch("/api/v1/nodes/{id}", task), Map.of("metadata", Map.of(col, "999"))), 400);
        call(s.member(), jsonBody(patch("/api/v1/nodes/{id}", task), Map.of("metadata", Map.of(col, "../../x"))), 400);
    }

    @Test
    void aLookupMustStayInsideTheSite() throws Exception {
        Setup s = setup();
        UUID elsewhere = list(s.owner(), id(createSite(s.owner(), "lists-b" + suffix)), "Other", "CUSTOM");
        addLookup(s.owner(), s.list(), elsewhere, 400);
        Map<String, Object> noTarget = Map.of("displayName", "Nothing", "type", "LOOKUP");
        call(s.owner(), jsonBody(post("/api/v1/nodes/{id}/columns", s.list()), noTarget), 400);
    }

    // ---- attachments

    @Test
    void attachmentsAreFilesThatFollowTheirItem() throws Exception {
        Setup s = setup();
        UUID item = item(s.member(), s.list(), "Printer jam", Map.of(internal(s.owner(), s.list(), "Status"), "In progress"));
        UUID file = id(upload(s.member(), item, "error.txt", "paper tray", false, 201));
        MockMultipartFile png = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3});
        call(s.member(), multipart("/api/v1/nodes/{id}/files", item).file(png), 201);

        JsonNode attached = call(s.visitor(), get("/api/v1/nodes/{id}/children", item), 200);
        assertThat(attached.findValuesAsText("name")).containsExactlyInAnyOrder("error.txt", "photo.png");
        assertThat(download(s.visitor(), file)).isEqualTo("paper tray");
        assertThat(call(s.visitor(), get("/api/v1/nodes/{id}/covers", s.list()), 200).get(item.toString())).isNotNull();

        // attachments do not appear as rows of the list
        assertThat(call(s.member(), get("/api/v1/nodes/{id}/children", s.list()), 200)).hasSize(1);
        upload(s.visitor(), item, "no.txt", "x", false, 403);

        call(s.owner(), delete("/api/v1/nodes/{id}", item), 204);
        call(s.member(), get("/api/v1/nodes/{id}/content", file), 404);
        call(s.owner(), post("/api/v1/recycle-bin/{id}/restore", item), 200);
        assertThat(download(s.member(), file)).isEqualTo("paper tray");
    }

    // ---- history

    @Test
    void everySaveIsKeptAndCanBeRestored() throws Exception {
        Setup s = setup();
        String status = internal(s.owner(), s.list(), "Status");
        UUID item = item(s.member(), s.list(), "Draft title", Map.of(status, "Not started"));

        call(s.member(), jsonBody(patch("/api/v1/nodes/{id}", item), Map.of("title", "Better title", "metadata", Map.of(status, "In progress"))), 200);
        call(s.owner(), jsonBody(patch("/api/v1/nodes/{id}", item), Map.of("metadata", Map.of(status, "Completed"))), 200);
        // saving without a change adds nothing
        call(s.owner(), jsonBody(patch("/api/v1/nodes/{id}", item), Map.of("metadata", Map.of(status, "Completed"))), 200);

        JsonNode history = call(s.visitor(), get("/api/v1/nodes/{id}/item-versions", item), 200);
        assertThat(history).hasSize(3);
        assertThat(history.get(0).get("current").asBoolean()).isTrue();
        assertThat(history.get(2).get("title").asText()).isEqualTo("Draft title");
        assertThat(history.get(1).get("values").get(status).asText()).isEqualTo("In progress");
        assertThat(history.get(2).get("createdBy").get("displayName").asText()).isEqualTo("member");

        call(s.visitor(), post("/api/v1/nodes/{id}/item-versions/1/restore", item), 403);
        JsonNode restored = call(s.member(), post("/api/v1/nodes/{id}/item-versions/1/restore", item), 200);
        assertThat(restored.get("title").asText()).isEqualTo("Draft title");
        assertThat(restored.get("metadata").get(status).asText()).isEqualTo("Not started");
        assertThat(call(s.member(), get("/api/v1/nodes/{id}/item-versions", item), 200)).hasSize(4);
    }
}
