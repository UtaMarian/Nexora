package io.nexora.search;

import org.junit.jupiter.api.AfterEach;

import java.io.IOException;

class LuceneSearchEngineTest extends SearchEngineContract {
    private final LuceneSearchEngine engine = new LuceneSearchEngine(new SearchProperties("lucene", false, null, 1, null, 1000,
            new SearchProperties.Lucene(""), null, null));

    LuceneSearchEngineTest() throws IOException {
    }

    @AfterEach
    void close() throws IOException {
        engine.close();
    }

    @Override
    protected SearchEngine engine() {
        return engine;
    }
}
