package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** The New menu: blank Office documents and copies of templates. */
class NewDocumentTest extends ApiTestSupport {

    private List<String> zipEntries(String login, UUID file) throws Exception {
        byte[] bytes = mvc.perform(get("/api/v1/nodes/{id}/content", file).with(httpBasic(login, login.equals(ADMIN) ? ADMIN_PW : PW)))
                .andReturn().getResponse().getContentAsByteArray();
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) names.add(e.getName());
        }
        return names;
    }

    private JsonNode create(String login, UUID parent, Map<String, Object> body, int status) throws Exception {
        return call(login, jsonBody(post("/api/v1/nodes/{id}/documents", parent), body), status);
    }

    @Test
    void createsRealBlankOfficeDocuments() throws Exception {
        String owner = createUser("owner");
        UUID lib = defaultLibrary(owner, id(createSite(owner, "newdoc-" + suffix)));

        JsonNode word = create(owner, lib, Map.of("type", "WORD", "name", "Minutes"), 201);
        assertThat(word.get("name").asText()).isEqualTo("Minutes.docx");
        assertThat(word.get("mimeType").asText()).contains("wordprocessingml");
        assertThat(word.get("version").asText()).isEqualTo("1.0");
        assertThat(zipEntries(owner, id(word))).contains("word/document.xml");

        assertThat(zipEntries(owner, id(create(owner, lib, Map.of("type", "EXCEL", "name", "Budget.xlsx"), 201)))).contains("xl/workbook.xml");
        assertThat(zipEntries(owner, id(create(owner, lib, Map.of("type", "POWERPOINT", "name", "Deck"), 201)))).contains("ppt/presentation.xml");
    }

    @Test
    void refusesWhatAnUploadWouldRefuse() throws Exception {
        String owner = createUser("owner");
        String visitor = createUser("visitor");
        UUID site = id(createSite(owner, "newdoc-" + suffix));
        UUID lib = defaultLibrary(owner, site);
        addToGroup(siteGroup(site, "visitors"), visitor);

        create(owner, lib, Map.of("type", "WORD", "name", "Same"), 201);
        create(owner, lib, Map.of("type", "WORD", "name", "Same"), 409);
        create(visitor, lib, Map.of("type", "WORD", "name", "Nope"), 403);
        create(owner, lib, Map.of("type", "WORD", "name", "  "), 400);
        create(owner, lib, Map.of("type", "WORD", "name", "bad*name"), 400);
        create(owner, lib, Map.of("name", "No type"), 400);
    }

    @Test
    void copiesATemplateFromTheTemplatesLibrary() throws Exception {
        String owner = createUser("owner");
        String member = createUser("member");
        UUID site = id(createSite(owner, "newdoc-" + suffix));
        UUID lib = defaultLibrary(owner, site);
        addToGroup(siteGroup(site, "members"), member);
        assertThat(call(owner, get("/api/v1/sites/{id}/document-templates", site), 200)).isEmpty();

        UUID templates = id(call(owner, jsonBody(post("/api/v1/sites/{id}/libraries", site), Map.of("name", "Templates")), 201));
        MockMultipartFile file = new MockMultipartFile("file", "Board paper.dotx", "application/octet-stream", "template text".getBytes());
        call(owner, multipart("/api/v1/nodes/{id}/files", templates).file(file), 201);

        JsonNode offered = call(member, get("/api/v1/sites/{id}/document-templates", site), 200);
        assertThat(offered.findValuesAsText("name")).containsExactly("Board paper.dotx");

        JsonNode copy = create(member, lib, Map.of("templateId", id(offered.get(0)).toString(), "name", "November paper"), 201);
        assertThat(copy.get("name").asText()).isEqualTo("November paper.dotx");
        assertThat(download(member, id(copy))).isEqualTo("template text");
        assertThat(call(member, get("/api/v1/nodes/{id}/versions", id(copy)), 200).get(0).get("comment").asText()).contains("Board paper.dotx");
    }

    @Test
    void aTemplateMustBeReadableByTheCreator() throws Exception {
        String owner = createUser("owner");
        String other = createUser("other");
        UUID secretLib = defaultLibrary(owner, id(createSite(owner, "newdoc-a" + suffix)));
        UUID secret = id(upload(owner, secretLib, "secret.docx", "secret", false, 201));
        UUID otherLib = defaultLibrary(other, id(createSite(other, "newdoc-b" + suffix)));
        create(other, otherLib, Map.of("templateId", secret.toString(), "name", "stolen"), 404);
    }
}
