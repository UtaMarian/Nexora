package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.office.OfficeService;
import io.nexora.content.NodeRepository;
import io.nexora.identity.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** WebDAV as desktop Office and Windows Explorer use it, over a real HTTP connection. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebDavTest extends ApiTestSupport {
    @LocalServerPort int port;
    @Autowired OfficeService office;
    @Autowired NodeRepository nodes;

    private final HttpClient http = HttpClient.newHttpClient();

    private String libPath;   // decoded, e.g. /sites/dav-x/Shared Documents
    private UUID libId;
    private String owner, member, visitor;

    private void setUp() throws Exception {
        owner = createUser("owner");
        member = createUser("member");
        visitor = createUser("visitor");
        UUID site = id(createSite(owner, "dav-" + suffix));
        addToGroup(siteGroup(site, "members"), member);
        addToGroup(siteGroup(site, "visitors"), visitor);
        libId = defaultLibrary(owner, site);
        libPath = "/sites/dav-" + suffix + "/Shared Documents";
    }

    private String url(String path) {
        StringBuilder b = new StringBuilder("http://localhost:" + port + "/dav");
        for (String seg : path.split("/")) {
            if (!seg.isEmpty()) b.append('/').append(UriUtils.encodePathSegment(seg, StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    private HttpResponse<String> dav(String login, String method, String path, String body, String... headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (login != null) {
            String pw = login.equals(ADMIN) ? ADMIN_PW : PW;
            b.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((login + ":" + pw).getBytes(StandardCharsets.UTF_8)));
        }
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final String LOCKINFO = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:lockinfo xmlns:D="DAV:"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype>
            <D:owner><D:href>Word</D:href></D:owner></D:lockinfo>""";

    private static String token(HttpResponse<String> lockResponse) {
        Matcher m = Pattern.compile("<(opaquelocktoken:[^>]+)>").matcher(lockResponse.headers().firstValue("Lock-Token").orElse(""));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private JsonNode versions(UUID fileId) throws Exception {
        return call(owner, get("/api/v1/nodes/{id}/versions", fileId), 200);
    }

    private UUID fileId(String path) throws Exception {
        return id(call(owner, get("/api/v1/resolve").param("path", path), 200).get("node"));
    }

    @Test
    void discoveryAndAuthentication() throws Exception {
        setUp();
        HttpResponse<String> opt = dav(null, "OPTIONS", libPath, null);
        assertThat(opt.statusCode()).isEqualTo(200);
        assertThat(opt.headers().firstValue("DAV").orElse("")).contains("2");
        assertThat(opt.headers().firstValue("MS-Author-Via").orElse("")).isEqualTo("DAV");

        HttpResponse<String> anon = dav(null, "PROPFIND", libPath, null, "Depth", "0");
        assertThat(anon.statusCode()).isEqualTo(401);
        assertThat(anon.headers().firstValue("WWW-Authenticate").orElse("")).contains("Basic realm=\"NEXORA\"");

        HttpResponse<String> sites = dav(member, "PROPFIND", "/sites", null, "Depth", "1");
        assertThat(sites.statusCode()).isEqualTo(207);
        assertThat(sites.body()).contains("/dav/sites/dav-" + suffix + "/");
    }

    @Test
    void putGetAndVersioning() throws Exception {
        setUp();
        String path = libPath + "/Budget 100%.xlsx";
        assertThat(dav(member, "PUT", path, "one").statusCode()).isEqualTo(201);
        assertThat(dav(member, "GET", path, null).body()).isEqualTo("one");
        assertThat(dav(member, "PUT", path, "two").statusCode()).isEqualTo(204);
        assertThat(versions(fileId(path))).extracting(v -> v.get("label").asText()).containsExactly("2.0", "1.0");

        // Explorer creates an empty file first, then uploads: no empty 1.0 left behind
        String copied = libPath + "/Copied.docx";
        assertThat(dav(member, "PUT", copied, "").statusCode()).isEqualTo(201);
        assertThat(dav(member, "PUT", copied, "real content").statusCode()).isEqualTo(204);
        assertThat(versions(fileId(copied))).extracting(v -> v.get("label").asText()).containsExactly("1.0");
        assertThat(dav(member, "GET", copied, null).body()).isEqualTo("real content");

        // some clients label the body as a form; it must still be stored as-is
        String form = libPath + "/form.txt";
        assertThat(dav(member, "PUT", form, "a=1&b=2", "Content-Type", "application/x-www-form-urlencoded").statusCode())
                .isEqualTo(201);
        assertThat(dav(member, "GET", form, null).body()).isEqualTo("a=1&b=2");

        HttpResponse<String> listing = dav(member, "PROPFIND", libPath, null, "Depth", "1");
        assertThat(listing.statusCode()).isEqualTo(207);
        assertThat(listing.body()).contains("Budget%20100%25.xlsx").contains("<D:getcontentlength>3</D:getcontentlength>");

        // readers can read but not write
        assertThat(dav(visitor, "GET", path, null).statusCode()).isEqualTo(200);
        assertThat(dav(visitor, "PUT", path, "nope").statusCode()).isEqualTo(403);
        assertThat(dav(visitor, "PUT", libPath + "/New.txt", "nope").statusCode()).isEqualTo(403);
    }

    @Test
    void lockBlocksOtherEditorsEverywhere() throws Exception {
        setUp();
        String path = libPath + "/Contract.docx";
        dav(owner, "PUT", path, "v1");
        UUID file = fileId(path);

        HttpResponse<String> lock = dav(member, "LOCK", path, LOCKINFO, "Timeout", "Second-600");
        assertThat(lock.statusCode()).isEqualTo(200);
        assertThat(lock.body()).contains("<D:lockscope><D:exclusive/></D:lockscope>").contains("Second-600");
        String token = token(lock);

        // others: WebDAV, browser upload and the browser editor are all blocked
        assertThat(dav(owner, "PUT", path, "clash").statusCode()).isEqualTo(423);
        assertThat(dav(owner, "LOCK", path, LOCKINFO).statusCode()).isEqualTo(423);
        upload(owner, libId, "Contract.docx", "clash", true, 423);
        assertThat(office.canWrite(CurrentUser.system(principal(owner)), nodes.findById(file).orElseThrow())
                ).isFalse();
        JsonNode listed = call(owner, get("/api/v1/nodes/{id}/children", libId), 200);
        assertThat(listed.get(0).get("lockedBy").get("login").asText()).isEqualTo(member);

        // the lock holder saves normally, and can refresh
        assertThat(dav(member, "PUT", path, "v2", "If", "(<" + token + ">)").statusCode()).isEqualTo(204);
        assertThat(dav(member, "LOCK", path, null, "If", "(<" + token + ">)", "Timeout", "Second-60").statusCode())
                .isEqualTo(200);

        assertThat(dav(member, "UNLOCK", path, null, "Lock-Token", "<" + token + ">").statusCode()).isEqualTo(204);
        assertThat(dav(owner, "PUT", path, "v3").statusCode()).isEqualTo(204);
        assertThat(versions(file)).extracting(v -> v.get("label").asText()).containsExactly("3.0", "2.0", "1.0");
    }

    @Test
    void lockingANewNameCreatesAnEmptyFile() throws Exception {
        setUp();
        String path = libPath + "/Fresh.docx";
        HttpResponse<String> lock = dav(member, "LOCK", path, LOCKINFO);
        assertThat(lock.statusCode()).isEqualTo(201);
        assertThat(dav(member, "PUT", path, "content", "If", "(<" + token(lock) + ">)").statusCode()).isEqualTo(204);
        assertThat(versions(fileId(path))).extracting(v -> v.get("label").asText()).containsExactly("1.0");
    }

    @Test
    void wordSafeSaveOnAMappedDriveKeepsVersionHistory() throws Exception {
        setUp();
        String doc = libPath + "/Report.docx";
        dav(member, "PUT", doc, "original");
        UUID file = fileId(doc);

        // what Word does on a mapped drive
        assertThat(dav(member, "PUT", libPath + "/~$Report.docx", "owner-file").statusCode()).isEqualTo(201);
        assertThat(dav(member, "PUT", libPath + "/~WRL0001.tmp", "edited").statusCode()).isEqualTo(201);
        assertThat(dav(member, "MOVE", doc, null, "Destination", url(libPath + "/~WRD0002.tmp")).statusCode())
                .isEqualTo(201);
        assertThat(dav(member, "PROPFIND", doc, null, "Depth", "0").statusCode()).isEqualTo(404);
        assertThat(dav(member, "MOVE", libPath + "/~WRL0001.tmp", null, "Destination", url(doc)).statusCode())
                .isIn(201, 204);
        assertThat(dav(member, "DELETE", libPath + "/~WRD0002.tmp", null).statusCode()).isEqualTo(204);
        assertThat(dav(member, "DELETE", libPath + "/~$Report.docx", null).statusCode()).isEqualTo(204);

        assertThat(fileId(doc)).isEqualTo(file);
        assertThat(versions(file)).extracting(v -> v.get("label").asText()).containsExactly("2.0", "1.0");
        assertThat(dav(member, "GET", doc, null).body()).isEqualTo("edited");
        JsonNode children = call(owner, get("/api/v1/nodes/{id}/children", libId), 200);
        assertThat(children).extracting(c -> c.get("name").asText()).containsExactly("Report.docx");
        assertThat(call(owner, get("/api/v1/recycle-bin"), 200))
                .noneMatch(i -> i.get("name").asText().equals("Report.docx"));
    }

    @Test
    void foldersMoveCopyAndDelete() throws Exception {
        setUp();
        assertThat(dav(member, "MKCOL", libPath + "/Projects", null).statusCode()).isEqualTo(201);
        assertThat(dav(member, "MKCOL", libPath + "/Projects", null).statusCode()).isEqualTo(405);
        assertThat(dav(member, "MKCOL", libPath + "/Nope/Deeper", null).statusCode()).isEqualTo(409);
        dav(member, "PUT", libPath + "/Projects/plan.txt", "plan");

        assertThat(dav(member, "MOVE", libPath + "/Projects", null,
                "Destination", url(libPath + "/Projects 2026")).statusCode()).isEqualTo(201);
        assertThat(dav(member, "GET", libPath + "/Projects 2026/plan.txt", null).body()).isEqualTo("plan");

        assertThat(dav(member, "COPY", libPath + "/Projects 2026/plan.txt", null,
                "Destination", url(libPath + "/plan-copy.txt")).statusCode()).isEqualTo(201);
        assertThat(dav(member, "COPY", libPath + "/Projects 2026/plan.txt", null,
                "Destination", url(libPath + "/plan-copy.txt"), "Overwrite", "F").statusCode()).isEqualTo(412);

        assertThat(dav(member, "DELETE", libPath + "/Projects 2026", null).statusCode()).isEqualTo(204);
        assertThat(dav(member, "PROPFIND", libPath + "/Projects 2026", null, "Depth", "0").statusCode()).isEqualTo(404);
        assertThat(call(member, get("/api/v1/recycle-bin"), 200))
                .anyMatch(i -> i.get("name").asText().equals("Projects 2026"));
    }

    @Test
    void mappedDrivesSeeSubsitesInsideTheirParentOnly() throws Exception {
        setUp();
        String site = "/sites/dav-" + suffix;
        UUID parent = fileId(site);
        UUID sub = id(call(owner, jsonBody(post("/api/v1/sites/{id}/subsites", parent), java.util.Map.of("url", "team", "title", "Team")), 201));
        upload(owner, defaultLibrary(owner, sub), "Doc.txt", "x", false, 201);

        assertThat(dav(owner, "PROPFIND", site, null, "Depth", "1").body()).contains("/dav" + site + "/team/");
        assertThat(dav(owner, "PROPFIND", site + "/team/Shared Documents", null, "Depth", "1").body()).contains("Doc.txt");
        assertThat(dav(owner, "GET", site + "/team/Shared Documents/Doc.txt", null).body()).isEqualTo("x");
        // the list of sites holds top-level sites: a deeper address there is not a child of that folder
        assertThat(dav(owner, "PROPFIND", "/sites", null, "Depth", "1").body())
                .contains("/dav" + site + "/").doesNotContain("/dav" + site + "/team");
    }

    @Test
    void hiddenItemsAreHiddenOverWebDavToo() throws Exception {
        setUp();
        UUID secret = id(call(owner, jsonBody(post("/api/v1/nodes/{id}/folders", libId),
                java.util.Map.of("name", "Board")), 201));
        call(owner, post("/api/v1/nodes/{id}/permissions/break-inheritance", secret), 200);
        call(owner, delete("/api/v1/nodes/{id}/permissions/{p}", secret, siteGroupId("visitors")), 200);

        assertThat(dav(visitor, "PROPFIND", libPath, null, "Depth", "1").body()).doesNotContain("Board");
        assertThat(dav(visitor, "PROPFIND", libPath + "/Board", null, "Depth", "0").statusCode()).isEqualTo(404);
    }

    private UUID siteGroupId(String role) throws Exception {
        JsonNode lib = call(ADMIN, get("/api/v1/nodes/{id}", libId), 200);
        return siteGroup(UUID.fromString(lib.get("node").get("siteId").asText()), role);
    }

    @Autowired io.nexora.identity.PrincipalRepository principals;

    private io.nexora.identity.Principal principal(String login) {
        return principals.findByKindAndLogin(io.nexora.identity.Principal.Kind.USER, login).orElseThrow();
    }
}
