package io.nexora.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties(prefix = "nexora.search")
public record SearchProperties(String engine, boolean workerEnabled, Duration pollInterval, int extractThreads,
                               DataSize maxFileSize, int maxTextChars, Lucene lucene, OpenSearch opensearch, Tika tika) {

    /** {@code path} empty keeps the index in memory (tests). */
    public record Lucene(String path) {}

    public record OpenSearch(String url, String index, String username, String password) {}

    /** {@code url} empty uses the built-in reader. */
    public record Tika(String url, Duration timeout) {}
}
