package io.nexora.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The production engine against a real server:
 * {@code NEXORA_TEST_OPENSEARCH_URL=http://localhost:9200 ./mvnw test -Dtest=OpenSearchEngineIT}
 */
@EnabledIfEnvironmentVariable(named = "NEXORA_TEST_OPENSEARCH_URL", matches = ".+")
class OpenSearchEngineIT extends SearchEngineContract {
    private final OpenSearchEngine engine = new OpenSearchEngine(new SearchProperties("opensearch", false, null, 1, null, 1000, null,
            new SearchProperties.OpenSearch(System.getenv("NEXORA_TEST_OPENSEARCH_URL"), "nexora-test-contract", "", ""), null), new ObjectMapper());

    @Override
    protected SearchEngine engine() {
        return engine;
    }
}
