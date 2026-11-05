package io.nexora.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.nexora.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** With the engine down, people still find things by name and are told results are partial. */
class SearchFallbackTest extends ApiTestSupport {
    @MockitoBean SearchEngine engine;

    @Test
    void fallsBackToNamesWhenTheEngineIsDown() throws Exception {
        when(engine.search(any(), any())).thenThrow(new SearchEngine.UnavailableException("down"));
        when(engine.status()).thenThrow(new SearchEngine.UnavailableException("down"));
        doReset();

        String owner = createUser("owner");
        String stranger = createUser("stranger");
        UUID site = id(createSite(owner, "fallback-" + suffix));
        String name = "report" + suffix + ".txt";
        upload(owner, defaultLibrary(owner, site), name, "text", false, 201);

        JsonNode r = call(owner, get("/api/v1/search").param("q", "report" + suffix), 200);
        assertThat(r.get("degraded").asBoolean()).isTrue();
        assertThat(r.get("hits").findValuesAsText("name")).containsExactly(name);

        assertThat(call(stranger, get("/api/v1/search").param("q", "report" + suffix), 200).get("total").asLong()).isZero();

        assertThat(call(ADMIN, get("/api/v1/admin/health"), 200).toString()).contains("Search index").contains("ERROR");
    }

    private void doReset() throws Exception {
        org.mockito.Mockito.doThrow(new SearchEngine.UnavailableException("down")).when(engine).reset();
        call(ADMIN, post("/api/v1/admin/search/rebuild"), 503);
    }
}
