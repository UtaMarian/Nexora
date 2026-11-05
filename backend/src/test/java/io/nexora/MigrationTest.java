package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** SharePoint migration against {@link FakeSharePoint}: scan, copy, permissions, delta sync, redirects. */
class MigrationTest extends ApiTestSupport {
    private static final String API = "/api/v1/admin/migration";

    private JsonNode waitForJob(JsonNode job) throws Exception {
        for (int i = 0; i < 300; i++) {
            JsonNode j = call(ADMIN, get("/api/v1/admin/jobs/{id}", id(job)), 200);
            String s = j.get("status").asText();
            if (s.equals("SUCCEEDED") || s.equals("FAILED") || s.equals("CANCELLED")) return j;
            Thread.sleep(100);
        }
        throw new AssertionError("The job did not finish");
    }

    private UUID source(FakeSharePoint sp) throws Exception {
        return id(call(ADMIN, jsonBody(post(API + "/sources"), Map.of("name", "Farm " + suffix, "baseUrl", sp.url(),
                "auth", "BASIC", "username", "svc", "secret", "p4ss")), 201));
    }

    private UUID task(UUID source, String target, Map<String, Object> options) throws Exception {
        return id(call(ADMIN, jsonBody(post(API + "/tasks"), Map.of("sourceId", source.toString(),
                "sourceWeb", "/sites/hr", "targetSite", target, "options", options)), 201));
    }

    private JsonNode copy(UUID task) throws Exception {
        return waitForJob(call(ADMIN, post(API + "/tasks/{id}/copy", task), 202));
    }

    private JsonNode node(String path) throws Exception {
        return call(ADMIN, get("/api/v1/resolve").param("path", path), 200).get("node");
    }

    private JsonNode redirect(String login, String url) throws Exception {
        return call(login, get("/api/v1/redirects/resolve").param("url", url), 200);
    }

    @Test
    void credentialsAreStoredEncryptedAndNeverReturned() throws Exception {
        String jdoe = createUser("jdoe");
        try (FakeSharePoint sp = new FakeSharePoint(jdoe, jdoe)) {
            JsonNode created = call(ADMIN, jsonBody(post(API + "/sources"), Map.of("name", "F", "baseUrl", sp.url() + "/",
                    "auth", "BASIC", "username", "svc", "secret", "p4ss")), 201);
            assertThat(created.toString()).doesNotContain("p4ss");
            assertThat(created.get("hasSecret").asBoolean()).isTrue();
            assertThat(created.get("baseUrl").asText()).isEqualTo(sp.url());

            assertThat(call(ADMIN, post(API + "/sources/{id}/test", id(created)), 200).get("ok").asBoolean()).isTrue();
            JsonNode found = call(ADMIN, post(API + "/sources/{id}/discover", id(created)), 200);
            assertThat(found.get(0).get("web").asText()).isEqualTo("/sites/hr");

            // a wrong password is reported, not thrown
            call(ADMIN, jsonBody(put(API + "/sources/{id}", id(created)), Map.of("name", "F", "baseUrl", sp.url(),
                    "auth", "BASIC", "username", "svc", "secret", "wrong")), 200);
            JsonNode bad = call(ADMIN, post(API + "/sources/{id}/test", id(created)), 200);
            assertThat(bad.get("ok").asBoolean()).isFalse();
            assertThat(bad.get("error").asText()).contains("Sign-in to SharePoint failed");

            call(jdoe, get(API + "/sources"), 403);
            call(ADMIN, delete(API + "/sources/{id}", id(created)), 204);
        }
    }

    @Test
    void scanReportsWhatCanBeCopied() throws Exception {
        String jdoe = createUser("jdoe");
        try (FakeSharePoint sp = new FakeSharePoint(jdoe, jdoe)) {
            UUID task = task(source(sp), "scan-" + suffix, Map.of());
            JsonNode job = waitForJob(call(ADMIN, jsonBody(post(API + "/tasks/{id}/scan", task), Map.of("deep", true)), 202));
            assertThat(job.get("status").asText()).isEqualTo("SUCCEEDED");

            JsonNode t = call(ADMIN, get(API + "/tasks/{id}", task), 200);
            assertThat(t.get("status").asText()).isEqualTo("SCANNED");
            JsonNode scan = t.get("scan");
            assertThat(scan.get("totals").get("libraries").asInt()).isEqualTo(2);
            assertThat(scan.get("totals").get("lists").asInt()).isEqualTo(2);
            assertThat(scan.get("totals").get("files").asInt()).isEqualTo(3);
            assertThat(scan.get("subwebs")).hasSize(1);
            assertThat(scan.get("warnings").toString()).contains("subsite").contains("unsupported");
            JsonNode pages = null;
            for (JsonNode l : scan.get("lists")) if (l.get("title").asText().equals("Site Pages")) pages = l;
            assertThat(pages.get("kind").asText()).isEqualTo("unsupported");
        }
    }

    @Test
    void copyBringsVersionsMetadataPermissionsAndThenOnlyChanges() throws Exception {
        String jdoe = createUser("jdoe");
        String msmith = createUser("msmith");
        String nobody = createUser("nobody");
        String target = "mig-" + suffix;
        String base = "/sites/" + target;
        try (FakeSharePoint sp = new FakeSharePoint(jdoe, msmith)) {
            UUID task = task(source(sp), target, Map.of("deleteMissing", true));
            JsonNode job = copy(task);
            assertThat(job.get("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(job.get("result").get("failed").asInt()).isZero();
            assertThat(job.get("log").asText()).contains("Column 'Linked'").contains("not copied").contains("ghost");

            // a lookup column, resolved although its list was copied after the list using it, and an attachment
            JsonNode closeBooks = node(base + "/Tasks/4");
            assertThat(closeBooks.get("metadata").get("Department").get("title").asText()).isEqualTo("Finance");
            assertThat(closeBooks.get("metadata").get("Department").get("lookupId").asInt()).isEqualTo(12);
            JsonNode tasksColumns = call(ADMIN, get("/api/v1/nodes/{id}/columns", id(node(base + "/Tasks"))), 200);
            assertThat(tasksColumns.findValuesAsText("lookupListId")).contains(id(node(base + "/Departments")).toString());
            assertThat(download(ADMIN, id(node(base + "/Tasks/4/plan.txt")))).isEqualTo("attached plan");

            // the file, with its whole history, labels, authors, dates and comments
            JsonNode report = node(base + "/Shared Documents/Report.docx");
            assertThat(report.get("version").asText()).isEqualTo("3.0");
            assertThat(download(ADMIN, id(report))).isEqualTo("report v3");
            assertThat(report.get("createdBy").get("login").asText()).isEqualTo(jdoe);
            assertThat(report.get("modifiedAt").asText()).startsWith("2024-03-03");
            JsonNode history = call(ADMIN, get("/api/v1/nodes/{id}/versions", id(report)), 200);
            assertThat(history).extracting(v -> v.get("label").asText()).containsExactly("3.0", "2.0", "1.0");
            assertThat(history.get(2).get("comment").asText()).isEqualTo("first draft");
            assertThat(history.get(2).get("createdBy").get("login").asText()).isEqualTo(msmith);

            // metadata: text, a choice SharePoint had but the field list did not, and a person
            assertThat(report.get("metadata").get("ProjectCode").asText()).isEqualTo("P-100");
            assertThat(report.get("metadata").get("Category").asText()).isEqualTo("Finance");
            assertThat(report.get("metadata").get("Reviewer").get("login").asText()).isEqualTo(msmith);

            // the Forms folder is not content; lists keep their item numbers
            call(ADMIN, get("/api/v1/resolve").param("path", base + "/Shared Documents/Forms"), 404);
            JsonNode taskItem = node(base + "/Tasks/4");
            assertThat(taskItem.get("title").asText()).isEqualTo("Close the books");
            assertThat(taskItem.get("metadata").get("TaskStatus").asText()).isEqualTo("Not started");
            assertThat(taskItem.get("createdBy").get("login").asText()).isEqualTo(jdoe);

            // groups and permissions: SharePoint's associated groups map to the site's groups
            UUID site = id(node(base));
            assertThat(call(ADMIN, get("/api/v1/principals/{id}/members", siteGroup(site, "owners")), 200).toString()).contains(jdoe);
            assertThat(call(ADMIN, get("/api/v1/principals/{id}/members", siteGroup(site, "members")), 200).toString()).contains(msmith);
            call(msmith, get("/api/v1/nodes/{id}", id(report)), 200);
            call(nobody, get("/api/v1/nodes/{id}", id(report)), 404);

            // a restricted library and a restricted file stay restricted
            UUID secret = id(node(base + "/Secret/Salaries.xlsx"));
            call(jdoe, get("/api/v1/nodes/{id}", secret), 200);
            call(msmith, get("/api/v1/nodes/{id}", secret), 404);
            UUID leave = id(node(base + "/Shared Documents/Policies/Leave.pdf"));
            assertThat(node(base + "/Shared Documents/Policies/Leave.pdf").get("inheritsPermissions").asBoolean()).isFalse();
            call(msmith, get("/api/v1/nodes/{id}", leave), 404);

            // redirects, for each kind of old link
            String host = sp.url();
            assertThat(redirect(jdoe, host + "/sites/hr/Shared%20Documents/Report.docx").get("path").asText()).isEqualTo(base + "/Shared Documents/Report.docx");
            assertThat(redirect(jdoe, "/_layouts/15/Doc.aspx?sourcedoc=%7B" + FakeSharePoint.REPORT.toUpperCase() + "%7D&action=edit").get("path").asText())
                    .isEqualTo(base + "/Shared Documents/Report.docx");
            assertThat(redirect(jdoe, "/:w:/r/sites/hr/Shared%20Documents/Report.docx?d=x").get("path").asText()).isEqualTo(base + "/Shared Documents/Report.docx");
            assertThat(redirect(jdoe, "/sites/hr/Shared%20Documents/Forms/AllItems.aspx").get("path").asText()).isEqualTo(base + "/Shared Documents");
            assertThat(redirect(jdoe, "/sites/hr/Lists/Tasks/DispForm.aspx?ID=4").get("path").asText()).isEqualTo(base + "/Tasks/4");
            assertThat(redirect(jdoe, "/sites/hr/Lists/Tasks/AllItems.aspx").get("path").asText()).isEqualTo(base + "/Tasks");
            assertThat(redirect(jdoe, "/sites/hr/SitePages/Home.aspx").get("path").asText()).isEqualTo(base);
            // a link must not reveal where restricted content went: the reader lands on what they may see
            assertThat(redirect(msmith, "/sites/hr/Secret/Salaries.xlsx").get("path").asText()).isEqualTo(base);
            assertThat(redirect(jdoe, "/sites/hr/Secret/Salaries.xlsx").get("path").asText()).isEqualTo(base + "/Secret/Salaries.xlsx");
            call(jdoe, get("/api/v1/redirects/resolve").param("url", "/nothing/here"), 404);

            // second run, nothing changed: no downloads, no new versions
            JsonNode again = copy(task);
            assertThat(again.get("type").asText()).isEqualTo("MIGRATION_DELTA");
            assertThat(again.get("result").get("created").asInt()).isZero();
            assertThat(again.get("result").get("updated").asInt()).isZero();
            assertThat(again.get("result").get("versions").asInt()).isZero();
            assertThat(again.get("result").get("unchanged").asInt()).isGreaterThanOrEqualTo(5);

            // changes in SharePoint: new version, new file, rename, deleted file, changed list item
            FakeSharePoint.SpFile f = sp.files.get(FakeSharePoint.REPORT);
            f.history.add(Map.of("ID", 1536, "VersionLabel", "3.0", "Created", "2024-03-03T10:00:00Z", "CheckInComment", "current",
                    "Url", "_vti_history/1536/Shared Documents/Report.docx", "CreatedBy", Map.of("LoginName", sp.jdoe, "Title", "J", "Email", "")));
            sp.blobs.put("_vti_history/1536/Shared Documents/Report.docx", "report v3");
            f.label = "4.0";
            f.modified = "2024-04-04T10:00:00Z";
            f.content = "report v4";
            f.name = "Annual Report.docx";
            f.fields.put("Project_x0020_Code", "P-200");
            sp.files.put("new", new FakeSharePoint.SpFile("bbbbbbbb-0000-0000-0000-00000000000a", "/sites/hr/Shared Documents", "New.txt", "1.0",
                    "2024-04-05T10:00:00Z", "new file", 9, sp.jdoe));
            sp.files.remove("bbbbbbbb-0000-0000-0000-000000000003");
            sp.tasks.set(0, sp.task(1, "Prepare the review", "In progress", "2024-04-06T09:00:00Z"));

            JsonNode delta = copy(task);
            assertThat(delta.get("result").get("failed").asInt()).isZero();
            assertThat(delta.get("result").get("created").asInt()).isEqualTo(1);
            assertThat(delta.get("result").get("updated").asInt()).isEqualTo(2);
            assertThat(delta.get("result").get("deleted").asInt()).isEqualTo(1);

            JsonNode renamed = node(base + "/Shared Documents/Annual Report.docx");
            assertThat(id(renamed)).isEqualTo(id(report));
            assertThat(renamed.get("version").asText()).isEqualTo("4.0");
            assertThat(renamed.get("metadata").get("ProjectCode").asText()).isEqualTo("P-200");
            assertThat(call(ADMIN, get("/api/v1/nodes/{id}/versions", id(report)), 200))
                    .extracting(v -> v.get("label").asText()).containsExactly("4.0", "3.0", "2.0", "1.0");
            assertThat(download(ADMIN, id(report))).isEqualTo("report v4");
            assertThat(download(ADMIN, id(node(base + "/Shared Documents/New.txt")))).isEqualTo("new file");
            assertThat(node(base + "/Tasks/1").get("title").asText()).isEqualTo("Prepare the review");
            call(ADMIN, get("/api/v1/resolve").param("path", base + "/Secret/Salaries.xlsx"), 404);   // in the recycle bin

            // the old link follows the rename
            assertThat(redirect(jdoe, "/sites/hr/Shared%20Documents/Annual%20Report.docx").get("path").asText())
                    .isEqualTo(base + "/Shared Documents/Annual Report.docx");
        }
    }

    @Test
    void userMappingsAndVersionLimitAreHonoured() throws Exception {
        String jdoe = createUser("jdoe");
        String msmith = createUser("msmith");
        String mapped = createUser("mapped");
        String target = "map-" + suffix;
        try (FakeSharePoint sp = new FakeSharePoint(jdoe, msmith)) {
            UUID task = task(source(sp), target, Map.of("versions", 1, "permissions", false,
                    "userMappings", Map.of("contoso\\" + msmith, mapped)));
            copy(task);
            JsonNode report = node("/sites/" + target + "/Shared Documents/Report.docx");
            JsonNode history = call(ADMIN, get("/api/v1/nodes/{id}/versions", id(report)), 200);
            assertThat(history).extracting(v -> v.get("label").asText()).containsExactly("3.0", "2.0");

            JsonNode leave = node("/sites/" + target + "/Shared Documents/Policies/Leave.pdf");
            assertThat(leave.get("createdBy").get("login").asText()).isEqualTo(mapped);
        }
    }

    @Test
    void restrictedContentStaysClosedWhenItsPermissionsCannotBeRead() throws Exception {
        String jdoe = createUser("jdoe");
        String msmith = createUser("msmith");
        String target = "closed-" + suffix;
        String base = "/sites/" + target;
        try (FakeSharePoint sp = new FakeSharePoint(jdoe, msmith)) {
            // copying works, reading the ACLs of the restricted library and file fails
            sp.failing.add("lists(guid'" + FakeSharePoint.SECRET + "')/roleassignments");
            sp.failing.add("/items(2)/roleassignments");
            JsonNode job = copy(task(source(sp), target, Map.of()));
            assertThat(job.get("result").get("failed").asInt()).isPositive();

            JsonNode t = call(ADMIN, get(API + "/tasks"), 200).get(0);
            assertThat(t.get("status").asText()).isEqualTo("FAILED");
            // msmith is a site member: they see open content but not what could not be secured
            call(msmith, get("/api/v1/nodes/{id}", id(node(base + "/Shared Documents/Report.docx"))), 200);
            call(msmith, get("/api/v1/nodes/{id}", id(node(base + "/Secret/Salaries.xlsx"))), 404);
            call(msmith, get("/api/v1/nodes/{id}", id(node(base + "/Shared Documents/Policies/Leave.pdf"))), 404);
        }
    }

    @Test
    void listIsNotCopiedWhenItsRestrictionsCannotBeRead() throws Exception {
        String jdoe = createUser("jdoe");
        String target = "skip-" + suffix;
        try (FakeSharePoint sp = new FakeSharePoint(jdoe, jdoe)) {
            sp.failing.add("lists(guid'" + FakeSharePoint.DOCS + "')/items");
            JsonNode job = copy(task(source(sp), target, Map.of()));
            assertThat(job.get("result").get("failed").asInt()).isPositive();
            call(ADMIN, get("/api/v1/resolve").param("path", "/sites/" + target + "/Shared Documents/Report.docx"), 404);
        }
    }
}
