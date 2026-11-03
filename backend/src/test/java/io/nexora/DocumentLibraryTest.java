package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class DocumentLibraryTest extends ApiTestSupport {

    @Test
    void siteCreationProvisionsGroupsAndDefaultLibrary() throws Exception {
        String owner = createUser("owner");
        JsonNode site = createSite(owner, "team-" + suffix);

        assertThat(site.get("path").asText()).isEqualTo("/sites/team-" + suffix);
        assertThat(site.get("myLevel").asText()).isEqualTo("FULL_CONTROL");

        JsonNode libs = call(owner, get("/api/v1/sites/{id}/libraries", id(site)), 200);
        assertThat(libs).hasSize(1);
        assertThat(libs.get(0).get("path").asText()).isEqualTo("/sites/team-" + suffix + "/Shared Documents");

        JsonNode groups = call(owner, get("/api/v1/sites/{id}/groups", id(site)), 200);
        assertThat(groups).extracting(g -> g.get("displayName").asText())
                .containsExactlyInAnyOrder("Site team-" + suffix + " Owners", "Site team-" + suffix + " Members",
                        "Site team-" + suffix + " Visitors");

        call(owner, jsonBody(post("/api/v1/sites"), Map.of("url", "team-" + suffix)), 409);
    }

    @Test
    void uploadsCreateMajorVersionsAndRestoreWorks() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "docs-" + suffix));
        UUID lib = defaultLibrary(owner, site);

        JsonNode file = upload(owner, lib, "Report.txt", "first", false, 201);
        assertThat(file.get("version").asText()).isEqualTo("1.0");
        UUID fileId = id(file);

        upload(owner, lib, "report.TXT", "clash", false, 409);  // names are case-insensitive, like SharePoint
        JsonNode v2 = upload(owner, lib, "Report.txt", "second", true, 201);
        assertThat(v2.get("version").asText()).isEqualTo("2.0");
        assertThat(download(owner, fileId)).isEqualTo("second");

        JsonNode history = call(owner, get("/api/v1/nodes/{id}/versions", fileId), 200);
        assertThat(history).extracting(v -> v.get("label").asText()).containsExactly("2.0", "1.0");
        UUID v1 = id(history.get(1));

        JsonNode restored = call(owner, post("/api/v1/nodes/{id}/versions/{v}/restore", fileId, v1), 200);
        assertThat(restored.get("version").asText()).isEqualTo("3.0");
        assertThat(download(owner, fileId)).isEqualTo("first");

        UUID v2Id = id(history.get(0));
        call(owner, delete("/api/v1/nodes/{id}/versions/{v}", fileId, v2Id), 204);
        JsonNode after = call(owner, get("/api/v1/nodes/{id}/versions", fileId), 200);
        assertThat(after).extracting(v -> v.get("label").asText()).containsExactly("3.0", "1.0");

        // the current version is protected
        UUID current = id(after.get(0));
        call(owner, delete("/api/v1/nodes/{id}/versions/{v}", fileId, current), 409);
    }

    @Test
    void minorVersionsAndPublishing() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "drafts-" + suffix));
        UUID lib = defaultLibrary(owner, site);
        call(owner, jsonBody(put("/api/v1/nodes/{id}/settings", lib), Map.of(
                "versioningEnabled", true, "minorVersionsEnabled", true, "requireCheckout", false)), 200);

        UUID file = id(upload(owner, lib, "Plan.docx", "a", false, 201));
        upload(owner, lib, "Plan.docx", "b", true, 201);
        JsonNode published = call(owner, jsonBody(post("/api/v1/nodes/{id}/publish", file), Map.of("comment", "ok")), 200);
        assertThat(published.get("version").asText()).isEqualTo("1.0");
        assertThat(published.get("mimeType").asText()).contains("wordprocessingml");

        JsonNode next = upload(owner, lib, "Plan.docx", "c", true, 201);
        assertThat(next.get("version").asText()).isEqualTo("1.1");
        call(owner, post("/api/v1/nodes/{id}/publish", file), 200);
        call(owner, post("/api/v1/nodes/{id}/publish", file), 409); // already published
    }

    @Test
    void checkOutKeepsAPrivateDraftUntilCheckIn() throws Exception {
        String owner = createUser("owner");
        String editor = createUser("editor");
        UUID site = id(createSite(owner, "lock-" + suffix));
        addToGroup(siteGroup(site, "members"), editor);
        UUID lib = defaultLibrary(owner, site);
        UUID file = id(upload(owner, lib, "Contract.txt", "v1", false, 201));

        JsonNode co = call(editor, post("/api/v1/nodes/{id}/checkout", file), 200);
        assertThat(co.get("checkedOutBy").get("login").asText()).isEqualTo(editor);

        upload(owner, lib, "Contract.txt", "owner edit", true, 423);   // locked for everyone else
        call(owner, jsonBody(patch("/api/v1/nodes/{id}", file), Map.of("name", "X.txt")), 423);

        upload(editor, lib, "Contract.txt", "draft", true, 201);
        assertThat(download(editor, file)).isEqualTo("draft");          // editor sees the draft
        assertThat(download(owner, file)).isEqualTo("v1");              // others still see the published version

        JsonNode in = call(editor, jsonBody(post("/api/v1/nodes/{id}/checkin", file), Map.of("comment", "edited")), 200);
        assertThat(in.get("version").asText()).isEqualTo("2.0");
        assertThat(in.has("checkedOutBy")).isFalse();
        assertThat(download(owner, file)).isEqualTo("draft");

        // owner (Full Control) can discard someone else's checkout; a member cannot discard the owner's
        call(editor, post("/api/v1/nodes/{id}/checkout", file), 200);
        upload(editor, lib, "Contract.txt", "abandoned", true, 201);
        call(owner, post("/api/v1/nodes/{id}/discard-checkout", file), 200);
        assertThat(download(editor, file)).isEqualTo("draft");
        call(owner, post("/api/v1/nodes/{id}/checkout", file), 200);
        call(editor, post("/api/v1/nodes/{id}/discard-checkout", file), 403);
    }

    @Test
    void requireCheckoutBlocksDirectEdits() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "strict-" + suffix));
        UUID lib = defaultLibrary(owner, site);
        UUID file = id(upload(owner, lib, "Policy.txt", "v1", false, 201));
        call(owner, jsonBody(put("/api/v1/nodes/{id}/settings", lib), Map.of(
                "versioningEnabled", true, "minorVersionsEnabled", false, "requireCheckout", true)), 200);

        JsonNode err = upload(owner, lib, "Policy.txt", "v2", true, 409);
        assertThat(err.get("code").asText()).isEqualTo("checkout_required");
        call(owner, post("/api/v1/nodes/{id}/checkout", file), 200);
        upload(owner, lib, "Policy.txt", "v2", true, 201);
        call(owner, post("/api/v1/nodes/{id}/checkin", file), 200);
        assertThat(download(owner, file)).isEqualTo("v2");
    }

    @Test
    void renamingAFolderRewritesDescendantPathsAndLinksResolve() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "paths-" + suffix));
        UUID lib = defaultLibrary(owner, site);
        UUID folder = id(call(owner, jsonBody(post("/api/v1/nodes/{id}/folders", lib), Map.of("name", "Old 100%")), 201));
        UUID sub = id(call(owner, jsonBody(post("/api/v1/nodes/{id}/folders", folder), Map.of("name", "Sub")), 201));
        UUID file = id(upload(owner, sub, "a.txt", "x", false, 201));

        call(owner, jsonBody(patch("/api/v1/nodes/{id}", folder), Map.of("name", "New")), 200);

        String base = "/sites/paths-" + suffix + "/Shared Documents/New/Sub/a.txt";
        JsonNode resolved = call(owner, get("/api/v1/resolve").param("path", base), 200);
        assertThat(id(resolved.get("node"))).isEqualTo(file);
        assertThat(resolved.get("breadcrumbs")).extracting(c -> c.get("name").asText())
                .containsExactly("paths-" + suffix, "Shared Documents", "New", "Sub", "a.txt");
        // URL-encoded form, as found in old SharePoint links
        call(owner, get("/api/v1/resolve").param("path", base.replace(" ", "%20")), 200);

        // move the file up to the library
        JsonNode moved = call(owner, jsonBody(post("/api/v1/nodes/{id}/move", file), Map.of("targetParentId", lib)), 200);
        assertThat(moved.get("path").asText()).isEqualTo("/sites/paths-" + suffix + "/Shared Documents/a.txt");
        // a folder cannot move into its own subtree
        call(owner, jsonBody(post("/api/v1/nodes/{id}/move", folder), Map.of("targetParentId", sub)), 400);
    }

    @Test
    void recycleBinRestoreAndPurge() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "bin-" + suffix));
        UUID lib = defaultLibrary(owner, site);
        UUID folder = id(call(owner, jsonBody(post("/api/v1/nodes/{id}/folders", lib), Map.of("name", "Archive")), 201));
        UUID file = id(upload(owner, folder, "old.txt", "old", false, 201));

        call(owner, delete("/api/v1/nodes/{id}", folder), 204);
        call(owner, get("/api/v1/nodes/{id}", file), 404);
        JsonNode bin = call(owner, get("/api/v1/recycle-bin").param("siteId", site.toString()), 200);
        assertThat(bin).extracting(i -> i.get("name").asText()).containsExactly("Archive");

        call(owner, post("/api/v1/recycle-bin/{id}/restore", folder), 200);
        assertThat(download(owner, file)).isEqualTo("old");

        call(owner, delete("/api/v1/nodes/{id}", folder), 204);
        call(owner, jsonBody(post("/api/v1/nodes/{id}/folders", lib), Map.of("name", "Archive")), 201);
        call(owner, post("/api/v1/recycle-bin/{id}/restore", folder), 409); // name taken in original location

        call(owner, delete("/api/v1/recycle-bin/{id}", folder), 204);
        assertThat(call(owner, get("/api/v1/recycle-bin").param("siteId", site.toString()), 200)).isEmpty();
    }

    @Test
    void metadataIsValidatedAgainstLibraryColumns() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "meta-" + suffix));
        UUID lib = defaultLibrary(owner, site);
        call(owner, jsonBody(post("/api/v1/nodes/{id}/columns", lib),
                Map.of("displayName", "Department", "type", "CHOICE", "required", false, "choices", List.of("HR", "IT"))), 201);
        call(owner, jsonBody(post("/api/v1/nodes/{id}/columns", lib),
                Map.of("displayName", "Review date", "type", "DATE", "required", false)), 201);
        UUID file = id(upload(owner, lib, "Handbook.pdf", "%PDF", false, 201));

        JsonNode ok = call(owner, jsonBody(patch("/api/v1/nodes/{id}", file),
                Map.of("metadata", Map.of("Department", "HR", "ReviewDate", "2027-01-31"))), 200);
        assertThat(ok.get("metadata").get("Department").asText()).isEqualTo("HR");

        call(owner, jsonBody(patch("/api/v1/nodes/{id}", file), Map.of("metadata", Map.of("Department", "Sales"))), 400);
        call(owner, jsonBody(patch("/api/v1/nodes/{id}", file), Map.of("metadata", Map.of("ReviewDate", "tomorrow"))), 400);
        call(owner, jsonBody(patch("/api/v1/nodes/{id}", file), Map.of("metadata", Map.of("Nope", 1))), 400);
    }

    @Test
    void invalidNamesAreRejected() throws Exception {
        String owner = createUser("owner");
        UUID site = id(createSite(owner, "names-" + suffix));
        UUID lib = defaultLibrary(owner, site);
        for (String bad : List.of("a:b", "what?", "x|y", "trailing.", "con", "  ")) {
            call(owner, jsonBody(post("/api/v1/nodes/{id}/folders", lib), Map.of("name", bad)), 400);
        }
        call(owner, jsonBody(post("/api/v1/sites"), Map.of("url", "Bad Url")), 400);
    }
}
