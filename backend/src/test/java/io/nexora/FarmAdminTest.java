package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Central Administration: overview, sites, backup, restore, import/export, jobs, logs and users. */
@TestPropertySource(properties = "nexora.admin.backup-dir=${java.io.tmpdir}/nexora-test-backups")
class FarmAdminTest extends ApiTestSupport {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    private JsonNode waitForJob(JsonNode job) throws Exception {
        for (int i = 0; i < 300; i++) {
            JsonNode j = call(ADMIN, get("/api/v1/admin/jobs/{id}", id(job)), 200);
            String s = j.get("status").asText();
            if (s.equals("SUCCEEDED") || s.equals("FAILED") || s.equals("CANCELLED")) return j;
            Thread.sleep(100);
        }
        throw new AssertionError("The job did not finish");
    }

    @Test
    void everythingRequiresAFarmAdministrator() throws Exception {
        String user = createUser("plain");
        for (String path : List.of("overview", "health", "sites", "backups", "jobs", "logs", "users", "settings", "migration/tasks")) {
            call(user, get("/api/v1/admin/" + path), 403);
            call(ADMIN, get("/api/v1/admin/" + path), 200);
        }
        call(user, post("/api/v1/admin/backups/farm"), 403);
    }

    @Test
    void overviewShowsCountsHealthAndSecretsStayHidden() throws Exception {
        String owner = createUser("owner");
        createSite(owner, "ov-" + suffix);
        JsonNode o = call(ADMIN, get("/api/v1/admin/overview"), 200);
        assertThat(o.get("counts").get("site").asInt()).isPositive();
        assertThat(o.get("health")).extracting(h -> h.get("component").asText()).contains("Database", "File storage", "Backup folder");
        for (JsonNode h : o.get("health")) {
            if (h.get("component").asText().equals("Database")) assertThat(h.get("status").asText()).isEqualTo("OK");
        }
        String settings = call(ADMIN, get("/api/v1/admin/settings"), 200).toString();
        assertThat(settings).contains("Storage").doesNotContain("test-secret").doesNotContain("admin-test-pw");
    }

    @Test
    void administratorsCreateAndDeleteSitesForOwners() throws Exception {
        String owner = createUser("owner");
        String url = "adm-" + suffix;
        JsonNode site = call(ADMIN, jsonBody(post("/api/v1/admin/sites"), Map.of("url", url, "title", "Admin made",
                "template", "COMMUNICATION", "owner", owner)), 201);
        UUID id = id(site);
        JsonNode row = null;
        for (JsonNode r : call(ADMIN, get("/api/v1/admin/sites"), 200)) if (r.get("name").asText().equals(url)) row = r;
        assertThat(row.get("owners")).hasSize(1);
        assertThat(row.get("template").asText()).isEqualTo("COMMUNICATION");
        call(owner, get("/api/v1/nodes/{id}", id), 200);

        call(ADMIN, jsonBody(post("/api/v1/admin/sites"), Map.of("url", url + "x", "owner", "no-such-user")), 400);
        call(ADMIN, delete("/api/v1/admin/sites/{id}", id), 204);
        call(owner, get("/api/v1/nodes/{id}", id), 404);
    }

    @Test
    void siteBackupRestoresEverythingUnderANewUrl() throws Exception {
        String owner = createUser("owner");
        String member = createUser("member");
        String auditor = createUser("auditor");
        String url = "bk-" + suffix;
        UUID site = id(createSite(owner, url));
        addToGroup(siteGroup(site, "members"), member);
        UUID lib = defaultLibrary(owner, site);

        call(owner, jsonBody(post("/api/v1/nodes/{id}/columns", lib), Map.of("displayName", "Dept", "type", "TEXT")), 201);
        UUID file = id(upload(owner, lib, "a.txt", "one", false, 201));
        upload(member, lib, "a.txt", "two", true, 201);
        call(owner, jsonBody(patch("/api/v1/nodes/{id}", file), Map.of("metadata", Map.of("Dept", "HR"))), 200);

        // a folder with its own permissions
        UUID folder = id(call(owner, jsonBody(post("/api/v1/nodes/{id}/folders", lib), Map.of("name", "Private")), 201));
        call(owner, post("/api/v1/nodes/{id}/permissions/break-inheritance", folder), 200);
        call(owner, jsonBody(put("/api/v1/nodes/{id}/permissions/{p}", folder, principalId(auditor)), Map.of("level", "READ")), 200);
        call(owner, delete("/api/v1/nodes/{id}/permissions/{p}", folder, siteGroup(site, "members")), 200);

        // a published home page whose image web part points at a file
        JsonNode image = call(owner, multipart("/api/v1/sites/{id}/page-images", site)
                .file(new MockMultipartFile("file", "b.png", "image/png", PNG)), 201);
        JsonNode page = call(owner, jsonBody(post("/api/v1/sites/{id}/pages", site), Map.of("title", "Home")), 201);
        UUID pageId = id(page.get("page"));
        call(owner, jsonBody(put("/api/v1/pages/{id}/draft", pageId), Map.of("title", "Home", "revision", page.get("revision").asInt(),
                "canvas", Map.of("sections", List.of(Map.of("layout", "one", "columns", List.of(List.of(
                        Map.of("id", "i", "type", "image", "props", Map.of("fileId", image.get("id").asText()))))))))), 200);
        call(owner, post("/api/v1/pages/{id}/publish", pageId), 200);
        call(owner, jsonBody(put("/api/v1/sites/{id}/home-page", site), Map.of("pageId", pageId.toString())), 204);

        JsonNode job = waitForJob(call(ADMIN, post("/api/v1/admin/sites/{id}/backup", site), 202));
        assertThat(job.get("status").asText()).isEqualTo("SUCCEEDED");
        String backup = job.get("result").get("backupId").asText();
        assertThat(call(ADMIN, get("/api/v1/admin/backups/{id}", backup), 200).get("sites").get(0).get("name").asText()).isEqualTo(url);

        String copy = "rs-" + suffix;
        JsonNode restore = waitForJob(call(ADMIN, jsonBody(post("/api/v1/admin/backups/{id}/restore", backup),
                Map.of("sites", List.of(Map.of("name", url, "targetName", copy, "title", "Restored")))), 202));
        assertThat(restore.get("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode restored = call(ADMIN, get("/api/v1/resolve").param("path", "/sites/" + copy), 200).get("node");
        UUID newSite = id(restored);
        assertThat(newSite).isNotEqualTo(site);
        assertThat(restored.get("title").asText()).isEqualTo("Restored");

        // file, versions with their authors, metadata and columns
        JsonNode f = call(ADMIN, get("/api/v1/resolve").param("path", "/sites/" + copy + "/Shared Documents/a.txt"), 200).get("node");
        assertThat(download(ADMIN, id(f))).isEqualTo("two");
        assertThat(f.get("metadata").get("Dept").asText()).isEqualTo("HR");
        JsonNode versions = call(ADMIN, get("/api/v1/nodes/{id}/versions", id(f)), 200);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("createdBy").get("login").asText()).isEqualTo(member);
        assertThat(versions.get(1).get("createdBy").get("login").asText()).isEqualTo(owner);

        // groups, and the broken folder keeps its exact permissions
        call(member, get("/api/v1/nodes/{id}", id(f)), 200);
        UUID newFolder = id(call(ADMIN, get("/api/v1/resolve").param("path", "/sites/" + copy + "/Shared Documents/Private"), 200).get("node"));
        call(member, get("/api/v1/nodes/{id}", newFolder), 404);
        call(auditor, get("/api/v1/nodes/{id}", newFolder), 200);

        // the page and its reference to the new copy of the image, and it is still the home page
        JsonNode design = call(owner, get("/api/v1/sites/{id}/design", newSite), 200);
        JsonNode newPage = call(ADMIN, get("/api/v1/pages/{id}", UUID.fromString(design.get("homePageId").asText())), 200);
        String fileId = newPage.get("published").get("canvas").get("sections").get(0).get("columns").get(0).get(0).get("props").get("fileId").asText();
        assertThat(fileId).isNotEqualTo(image.get("id").asText());
        assertThat(call(ADMIN, get("/api/v1/nodes/{id}", UUID.fromString(fileId)), 200).get("node").get("name").asText()).isEqualTo("b.png");

        // an existing URL is never overwritten
        JsonNode skipped = waitForJob(call(ADMIN, jsonBody(post("/api/v1/admin/backups/{id}/restore", backup),
                Map.of("sites", List.of(Map.of("name", url)))), 202));
        assertThat(skipped.get("log").asText()).contains("already exists");

        // export the package, import it as an upload, restore again
        MvcResult dl = mvc.perform(as(ADMIN, get("/api/v1/admin/backups/{id}/sites/{s}/package", backup, url))).andReturn();
        assertThat(dl.getResponse().getStatus()).isEqualTo(200);
        byte[] zip = dl.getResponse().getContentAsByteArray();
        JsonNode uploaded = call(ADMIN, multipart("/api/v1/admin/import").file(new MockMultipartFile("file", "p.zip", "application/zip", zip)), 201);
        assertThat(uploaded.get("kind").asText()).isEqualTo("UPLOAD");
        String imported = "im-" + suffix;
        JsonNode importJob = waitForJob(call(ADMIN, jsonBody(post("/api/v1/admin/backups/{id}/restore", uploaded.get("id").asText()),
                Map.of("sites", List.of(Map.of("name", url, "targetName", imported)))), 202));
        assertThat(importJob.get("status").asText()).isEqualTo("SUCCEEDED");
        call(ADMIN, get("/api/v1/resolve").param("path", "/sites/" + imported + "/Shared Documents/a.txt"), 200);

        call(ADMIN, delete("/api/v1/admin/backups/{id}", uploaded.get("id").asText()), 204);
        call(ADMIN, get("/api/v1/admin/backups/{id}", uploaded.get("id").asText()), 404);
    }

    @Test
    void importRejectsAnythingThatIsNotAPackage() throws Exception {
        call(ADMIN, multipart("/api/v1/admin/import").file(new MockMultipartFile("file", "x.zip", "application/zip", "not a zip".getBytes())), 400);
        // ids that could leave the backup folder never reach the disk
        call(ADMIN, get("/api/v1/admin/backups/{id}", "UPPER_case.."), 404);
        int traversal = mvc.perform(as(ADMIN, get(java.net.URI.create("/api/v1/admin/backups/..%2f..%2fetc")))).andReturn().getResponse().getStatus();
        assertThat(traversal).isIn(400, 404);
    }

    @Test
    void aDamagedPackageIsRefusedAndLeavesNoSite() throws Exception {
        String owner = createUser("owner");
        String url = "dmg-" + suffix;
        UUID site = id(createSite(owner, url));
        upload(owner, defaultLibrary(owner, site), "a.txt", "content", false, 201);
        JsonNode job = waitForJob(call(ADMIN, post("/api/v1/admin/sites/{id}/backup", site), 202));
        String backup = job.get("result").get("backupId").asText();

        // flip the stored content so its checksum no longer matches
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "nexora-test-backups", backup, "sites", url + ".zip");
        Path damaged = Files.createTempFile("damaged", ".zip");
        try (var in = new java.util.zip.ZipFile(root.toFile());
             var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(damaged))) {
            for (var e : java.util.Collections.list(in.entries())) {
                out.putNextEntry(new java.util.zip.ZipEntry(e.getName()));
                if (e.getName().startsWith("blobs/")) out.write("tampered".getBytes());
                else in.getInputStream(e).transferTo(out);
                out.closeEntry();
            }
        }
        Files.copy(damaged, root, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        String target = "dmg2-" + suffix;
        JsonNode restore = waitForJob(call(ADMIN, jsonBody(post("/api/v1/admin/backups/{id}/restore", backup),
                Map.of("sites", List.of(Map.of("name", url, "targetName", target)))), 202));
        assertThat(restore.get("status").asText()).isEqualTo("FAILED");
        assertThat(restore.get("message").asText()).contains("damaged");
        call(ADMIN, get("/api/v1/resolve").param("path", "/sites/" + target), 404);
    }

    @Test
    void farmBackupRestoresADeletedSiteAndMissingAccounts() throws Exception {
        String owner = createUser("owner");
        String url = "fb-" + suffix;
        UUID site = id(createSite(owner, url));
        upload(owner, defaultLibrary(owner, site), "a.txt", "keep me", false, 201);

        JsonNode job = waitForJob(call(ADMIN, post("/api/v1/admin/backups/farm"), 202));
        assertThat(job.get("status").asText()).isEqualTo("SUCCEEDED");
        String backup = job.get("result").get("backupId").asText();
        JsonNode info = call(ADMIN, get("/api/v1/admin/backups/{id}", backup), 200);
        assertThat(info.get("kind").asText()).isEqualTo("FARM");
        assertThat(info.get("includesDirectory").asBoolean()).isTrue();
        assertThat(info.get("sites")).extracting(s -> s.get("name").asText()).contains(url);

        // the original URL stays taken while the site sits in the recycle bin, so restore it elsewhere
        call(ADMIN, delete("/api/v1/admin/sites/{id}", site), 204);
        String target = "fb2-" + suffix;
        JsonNode restore = waitForJob(call(ADMIN, jsonBody(post("/api/v1/admin/backups/{id}/restore", backup),
                Map.of("directory", true, "sites", List.of(Map.of("name", url, "targetName", target)))), 202));
        assertThat(restore.get("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode f = call(ADMIN, get("/api/v1/resolve").param("path", "/sites/" + target + "/Shared Documents/a.txt"), 200).get("node");
        assertThat(download(owner, id(f))).isEqualTo("keep me");

        // once purged, the original URL is free: the site comes back exactly where it was
        call(ADMIN, delete("/api/v1/recycle-bin/{id}", site), 204);
        JsonNode back = waitForJob(call(ADMIN, jsonBody(post("/api/v1/admin/backups/{id}/restore", backup),
                Map.of("sites", List.of(Map.of("name", url)))), 202));
        assertThat(back.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(back.get("log").asText()).doesNotContain("already exists");
        JsonNode original = call(ADMIN, get("/api/v1/resolve").param("path", "/sites/" + url + "/Shared Documents/a.txt"), 200).get("node");
        assertThat(download(owner, id(original))).isEqualTo("keep me");
    }

    @Test
    void logsCanBeSearchedAndUsersManaged() throws Exception {
        String marker = "marker-" + suffix;
        LoggerFactory.getLogger("io.nexora.test").warn("Something odd: {}", marker);
        JsonNode logs = call(ADMIN, get("/api/v1/admin/logs").param("level", "WARN").param("q", marker), 200);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("level").asText()).isEqualTo("WARN");
        assertThat(call(ADMIN, get("/api/v1/admin/logs").param("level", "ERROR").param("q", marker), 200)).isEmpty();

        String user = createUser("toggle");
        UUID id = principalId(user);
        JsonNode row = call(ADMIN, jsonBody(patch("/api/v1/admin/users/{id}", id), Map.of("enabled", false)), 200);
        assertThat(row.get("enabled").asBoolean()).isFalse();
        call(ADMIN, jsonBody(patch("/api/v1/admin/users/{id}", id), Map.of("enabled", true)), 200);

        UUID me = principalId(ADMIN);
        call(ADMIN, jsonBody(patch("/api/v1/admin/users/{id}", me), Map.of("admin", false)), 409);
        call(ADMIN, jsonBody(patch("/api/v1/admin/users/{id}", me), Map.of("enabled", false)), 409);
    }
}
