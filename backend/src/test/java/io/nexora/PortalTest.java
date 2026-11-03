package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Site templates, branding, lists and list items. */
class PortalTest extends ApiTestSupport {

    private JsonNode createSite(String owner, String url, String template) throws Exception {
        return call(owner, jsonBody(post("/api/v1/sites"),
                Map.of("url", url, "title", "Portal " + url, "template", template)), 201);
    }

    private List<String> names(JsonNode arr) {
        return java.util.stream.StreamSupport.stream(arr.spliterator(), false).map(n -> n.get("title").asText()).toList();
    }

    @Test
    void templatesProvisionLibrariesListsAndNavigation() throws Exception {
        String owner = createUser("owner");

        UUID project = id(createSite(owner, "proj-" + suffix, "PROJECT"));
        assertThat(names(call(owner, get("/api/v1/sites/{id}/lists", project), 200)))
                .containsExactlyInAnyOrder("Tasks", "Issues", "Calendar");
        assertThat(names(call(owner, get("/api/v1/sites/{id}/libraries", project), 200)))
                .containsExactlyInAnyOrder("Documents", "Deliverables");
        JsonNode design = call(owner, get("/api/v1/sites/{id}/design", project), 200);
        assertThat(design.get("template").asText()).isEqualTo("PROJECT");
        assertThat(design.get("design").get("theme").asText()).isEqualTo("purple");
        assertThat(design.get("design").get("navigation")).extracting(l -> l.get("label").asText())
                .containsExactly("Home", "Tasks", "Issues", "Calendar", "Documents", "Deliverables");

        UUID intranet = id(createSite(owner, "intra-" + suffix, "INTRANET"));
        JsonNode d = call(owner, get("/api/v1/sites/{id}/design", intranet), 200).get("design");
        assertThat(d.get("header").get("layout").asText()).isEqualTo("extended");
        assertThat(d.get("home").get("showNews").asBoolean()).isTrue();
        UUID news = UUID.fromString(d.get("home").get("newsListId").asText());
        assertThat(call(owner, get("/api/v1/nodes/{id}", news), 200).get("node").get("title").asText()).isEqualTo("News");

        // Tasks come with SharePoint's columns
        UUID tasks = id(java.util.stream.StreamSupport.stream(
                call(owner, get("/api/v1/sites/{id}/lists", project), 200).spliterator(), false)
                .filter(l -> l.get("title").asText().equals("Tasks")).findFirst().orElseThrow());
        assertThat(call(owner, get("/api/v1/nodes/{id}/columns", tasks), 200)).extracting(c -> c.get("internalName").asText())
                .contains("Status", "Priority", "AssignedTo", "DueDate");

        JsonNode templates = call(owner, get("/api/v1/templates"), 200);
        assertThat(templates.get("sites")).hasSize(4);
        assertThat(templates.get("lists")).hasSizeGreaterThanOrEqualTo(7);
    }

    @Test
    void designIsValidatedAndRestrictedToOwners() throws Exception {
        String owner = createUser("owner");
        String member = createUser("member");
        UUID site = id(createSite(owner, "look-" + suffix, "TEAM"));
        addToGroup(siteGroup(site, "members"), member);

        ObjectNode design = (ObjectNode) call(owner, get("/api/v1/sites/{id}/design", site), 200).get("design").deepCopy();
        design.put("theme", "orange");
        ((ObjectNode) design.get("header")).put("layout", "compact");
        design.set("navigation", json.valueToTree(List.of(
                Map.of("label", "Home", "url", "/sites/look-" + suffix),
                Map.of("label", "Handbook", "url", "https://example.org/handbook"))));
        ((ObjectNode) design.get("home")).put("welcome", "Hello team");

        JsonNode saved = call(owner, jsonBody(put("/api/v1/sites/{id}/design", site), design), 200).get("design");
        assertThat(saved.get("theme").asText()).isEqualTo("orange");
        assertThat(saved.get("navigation")).hasSize(2);
        assertThat(call(member, get("/api/v1/sites/{id}/design", site), 200).get("design").get("home").get("welcome").asText())
                .isEqualTo("Hello team");

        call(member, jsonBody(put("/api/v1/sites/{id}/design", site), design), 403);

        for (String bad : List.of("javascript:alert(1)", "//evil.example", "data:text/html,x", "ftp://x")) {
            design.set("navigation", json.valueToTree(List.of(Map.of("label", "X", "url", bad))));
            call(owner, jsonBody(put("/api/v1/sites/{id}/design", site), design), 400);
        }
        design.set("navigation", json.valueToTree(List.of()));
        design.put("theme", "neon");
        call(owner, jsonBody(put("/api/v1/sites/{id}/design", site), design), 400);
    }

    @Test
    void logoUploadAcceptsOnlyRealImages() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "logo-" + suffix, "TEAM"));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

        JsonNode d = call(owner, multipart(HttpMethod.PUT, "/api/v1/sites/{id}/logo", site)
                .file(new MockMultipartFile("file", "logo.png", "image/png", png)), 200);
        assertThat(d.get("logoUrl").asText()).startsWith("/api/v1/sites/" + site + "/logo?v=");

        var res = mvc.perform(as(owner, get("/api/v1/sites/{id}/logo", site))).andReturn().getResponse();
        assertThat(res.getContentType()).isEqualTo("image/png");
        assertThat(res.getContentAsByteArray()).isEqualTo(png);

        // an SVG or HTML file renamed to .png is refused
        call(owner, multipart(HttpMethod.PUT, "/api/v1/sites/{id}/logo", site)
                .file(new MockMultipartFile("file", "logo.png", "image/png", "<svg onload=alert(1)>".getBytes())), 400);

        assertThat(call(owner, delete("/api/v1/sites/{id}/logo", site), 200).has("logoUrl")).isFalse();
    }

    @Test
    void listItemsAreValidatedNumberedAndPermissioned() throws Exception {
        String owner = createUser("owner");
        String member = createUser("member");
        String visitor = createUser("visitor");
        UUID site = id(createSite(owner, "lists-" + suffix, "TEAM"));
        addToGroup(siteGroup(site, "members"), member);
        addToGroup(siteGroup(site, "visitors"), visitor);

        UUID list = id(call(member, jsonBody(post("/api/v1/sites/{id}/lists", site),
                Map.of("name", "Tasks", "template", "TASKS")), 201));
        call(visitor, jsonBody(post("/api/v1/sites/{id}/lists", site), Map.of("name", "Nope")), 403);

        JsonNode first = call(member, jsonBody(post("/api/v1/nodes/{id}/items", list), Map.of(
                "title", "Prepare budget",
                "values", Map.of("Status", "In progress", "AssignedTo", owner, "DueDate", "2026-12-01"))), 201);
        assertThat(first.get("name").asText()).isEqualTo("1");
        assertThat(first.get("path").asText()).isEqualTo("/sites/lists-" + suffix + "/Tasks/1");
        assertThat(first.get("metadata").get("AssignedTo").get("login").asText()).isEqualTo(owner);

        JsonNode second = call(member, jsonBody(post("/api/v1/nodes/{id}/items", list), Map.of(
                "title", "Book venue", "values", Map.of("Status", "Not started"))), 201);
        assertThat(second.get("name").asText()).isEqualTo("2");

        // validation
        call(member, jsonBody(post("/api/v1/nodes/{id}/items", list), Map.of("title", "x")), 400);                 // Status required
        call(member, jsonBody(post("/api/v1/nodes/{id}/items", list), Map.of("title", "",
                "values", Map.of("Status", "Completed"))), 400);                                                  // Title required
        call(member, jsonBody(post("/api/v1/nodes/{id}/items", list), Map.of("title", "x",
                "values", Map.of("Status", "Completed", "AssignedTo", "nobody-" + suffix))), 400);                // unknown person
        call(visitor, jsonBody(post("/api/v1/nodes/{id}/items", list), Map.of("title", "x",
                "values", Map.of("Status", "Completed"))), 403);

        // edit and delete
        JsonNode edited = call(member, jsonBody(patch("/api/v1/nodes/{id}", id(first)), Map.of(
                "title", "Prepare budget 2027", "metadata", Map.of("Status", "Completed"))), 200);
        assertThat(edited.get("metadata").get("Status").asText()).isEqualTo("Completed");
        call(member, jsonBody(patch("/api/v1/nodes/{id}", id(first)), Map.of("name", "99")), 400);

        assertThat(call(visitor, get("/api/v1/nodes/{id}/children", list), 200)).hasSize(2);
        call(member, delete("/api/v1/nodes/{id}", id(second)), 204);
        assertThat(call(visitor, get("/api/v1/nodes/{id}/children", list), 200)).hasSize(1);

        // numbering continues after deletion, like SharePoint IDs
        JsonNode third = call(member, jsonBody(post("/api/v1/nodes/{id}/items", list), Map.of(
                "title", "Send invites", "values", Map.of("Status", "Not started"))), 201);
        assertThat(third.get("name").asText()).isEqualTo("3");
    }

    @Test
    void linkColumnsOnlyAcceptSafeUrls() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "links-" + suffix, "TEAM"));
        UUID list = id(call(owner, jsonBody(post("/api/v1/sites/{id}/lists", site),
                Map.of("name", "Links", "template", "LINKS")), 201));
        call(owner, jsonBody(post("/api/v1/nodes/{id}/items", list), Map.of("title", "Docs",
                "values", Map.of("URL", "https://docs.example.org"))), 201);
        call(owner, jsonBody(post("/api/v1/nodes/{id}/items", list), Map.of("title", "Bad",
                "values", Map.of("URL", "javascript:alert(document.cookie)"))), 400);
    }
}
