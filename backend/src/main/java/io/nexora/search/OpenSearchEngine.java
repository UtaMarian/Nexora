package io.nexora.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** OpenSearch over its REST API. The index name carries a version, so a changed mapping rebuilds by itself. */
@Component
@ConditionalOnProperty(name = "nexora.search.engine", havingValue = "opensearch")
public class OpenSearchEngine implements SearchEngine {
    private static final Logger log = LoggerFactory.getLogger(OpenSearchEngine.class);
    private static final List<String> FIELDS =
            List.of("title^5", "name_text^4", "description^2", "meta^2", "author", "content");
    private static final List<String> SNIPPET_FIELDS = List.of("content", "description", "meta");
    private static final String FLAGS = "AND|OR|NOT|PREFIX|PHRASE|PRECEDENCE|ESCAPE|WHITESPACE";
    private static final String SETTINGS = """
            {
              "settings": {
                "index": { "number_of_shards": 1, "number_of_replicas": 0, "max_terms_count": 1000000 },
                "analysis": {
                  "filter": {
                    "nexora_possessive": { "type": "stemmer", "language": "possessive_english" },
                    "nexora_stem": { "type": "stemmer", "language": "english" }
                  },
                  "tokenizer": {
                    "nexora_trigrams": { "type": "ngram", "min_gram": 3, "max_gram": 3, "token_chars": ["letter", "digit"] }
                  },
                  "analyzer": {
                    "nexora_text": {
                      "tokenizer": "standard",
                      "filter": ["nexora_possessive", "lowercase", "asciifolding", "nexora_stem"]
                    },
                    "nexora_trigram": { "tokenizer": "nexora_trigrams", "filter": ["lowercase", "asciifolding"] }
                  }
                }
              },
              "mappings": {
                "dynamic": "strict",
                "properties": {
                  "type": { "type": "keyword" },
                  "extension": { "type": "keyword" },
                  "site": { "type": "keyword" },
                  "site_id": { "type": "keyword" },
                  "acl_source": { "type": "keyword" },
                  "path": { "type": "keyword", "index": false, "doc_values": false },
                  "name_ngram": { "type": "text", "analyzer": "nexora_trigram", "norms": false },
                  "name_text": { "type": "text", "analyzer": "nexora_text" },
                  "title": { "type": "text", "analyzer": "nexora_text" },
                  "description": { "type": "text", "analyzer": "nexora_text", "index_options": "offsets" },
                  "meta": { "type": "text", "analyzer": "nexora_text", "index_options": "offsets" },
                  "content": { "type": "text", "analyzer": "nexora_text", "index_options": "offsets" },
                  "author": {
                    "type": "text", "analyzer": "nexora_text",
                    "fields": { "raw": { "type": "keyword", "ignore_above": 512 } }
                  },
                  "modified": { "type": "date", "format": "epoch_millis" },
                  "size": { "type": "long" }
                }
              }
            }
            """;

    private final String base;
    private final String index;
    private final String authorization;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();

    public OpenSearchEngine(SearchProperties properties, ObjectMapper json) {
        var cfg = properties.opensearch();
        this.base = cfg.url().replaceAll("/+$", "");
        this.index = cfg.index();
        this.json = json;
        this.authorization = cfg.username() == null || cfg.username().isBlank() ? null : "Basic " + Base64.getEncoder()
                .encodeToString((cfg.username() + ":" + cfg.password()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String name() {
        return "OpenSearch";
    }

    @Override
    public boolean ensureIndex() {
        if (send("GET", "/" + index + "/_mapping", null, null, 404).statusCode() == 200) return false;
        HttpResponse<String> created = send("PUT", "/" + index, SETTINGS, "application/json", 400);
        // another server may have created it first; that server fills it
        if (created.statusCode() == 400 && created.body().contains("resource_already_exists_exception")) return false;
        if (created.statusCode() >= 300) throw new UnavailableException("OpenSearch refused the index: " + created.body());
        log.info("Created search index {}", index);
        return true;
    }

    @Override
    public void index(List<SearchDocument> documents) {
        if (documents.isEmpty()) return;
        try {
            StringBuilder ndjson = new StringBuilder();
            for (SearchDocument d : documents) {
                ndjson.append(json.writeValueAsString(Map.of("index", Map.of("_id", d.id().toString())))).append('\n');
                ndjson.append(json.writeValueAsString(source(d))).append('\n');
            }
            bulk(ndjson.toString());
        } catch (IOException e) {
            throw new UnavailableException("Could not prepare documents for OpenSearch", e);
        }
    }

    private static Map<String, Object> source(SearchDocument d) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", d.type());
        m.put("extension", d.extension());
        m.put("site", d.site());
        m.put("site_id", d.siteId().toString());
        m.put("acl_source", d.aclSource().toString());
        m.put("path", d.path());
        m.put("name_ngram", d.name());
        m.put("name_text", d.nameText());
        m.put("title", d.title());
        m.put("description", d.description());
        m.put("meta", d.meta());
        m.put("content", d.content());
        m.put("author", d.author());
        m.put("modified", d.modifiedAt().toEpochMilli());
        m.put("size", d.sizeBytes());
        return m;
    }

    private void bulk(String ndjson) {
        JsonNode result = read(send("POST", "/" + index + "/_bulk", ndjson, "application/x-ndjson"));
        if (!result.path("errors").asBoolean()) return;
        int shown = 0;
        for (JsonNode item : result.path("items")) {
            JsonNode op = item.elements().next();
            int status = op.path("status").asInt();
            if (status >= 300 && status != 404 && shown++ < 3) {
                log.warn("OpenSearch rejected {}: {}", op.path("_id").asText(), op.path("error"));
            }
        }
    }

    @Override
    public void delete(Collection<UUID> ids) {
        if (ids.isEmpty()) return;
        StringBuilder ndjson = new StringBuilder();
        for (UUID id : ids) ndjson.append("{\"delete\":{\"_id\":\"").append(id).append("\"}}\n");
        bulk(ndjson.toString());
    }

    @Override
    public void deleteSite(UUID siteId) {
        send("POST", "/" + index + "/_delete_by_query?conflicts=proceed",
                write(Map.of("query", Map.of("term", Map.of("site_id", siteId.toString())))), "application/json");
    }

    @Override
    public Results search(SearchQuery q, Set<UUID> aclSources) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", q.from());
        body.put("size", q.size());
        body.put("track_total_hits", true);
        body.put("_source", false);
        body.put("query", query(q, aclSources));
        if (q.sort() == SearchQuery.Sort.MODIFIED) body.put("sort", List.of(Map.of("modified", "desc")));
        if (q.text() != null) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (String f : SNIPPET_FIELDS) fields.put(f, Map.of());
            body.put("highlight", Map.of("type", "unified", "pre_tags", List.of(MATCH_START), "post_tags", List.of(MATCH_END),
                    "fragment_size", 220, "number_of_fragments", 1, "fields", fields));
        }
        body.put("aggs", aggregations());
        JsonNode r = read(send("POST", "/" + index + "/_search", write(body), "application/json"));

        List<Hit> hits = new ArrayList<>();
        for (JsonNode h : r.path("hits").path("hits")) {
            String snippet = null;
            for (String f : SNIPPET_FIELDS) {
                JsonNode found = h.path("highlight").path(f);
                if (found.size() > 0) {
                    snippet = found.get(0).asText();
                    break;
                }
            }
            hits.add(new Hit(UUID.fromString(h.path("_id").asText()), snippet));
        }
        return new Results(r.path("hits").path("total").path("value").asLong(), hits, facets(r.path("aggregations")));
    }

    private Map<String, Object> query(SearchQuery q, Set<UUID> aclSources) {
        List<Object> filters = new ArrayList<>();
        if (aclSources != null) filters.add(terms("acl_source", aclSources.stream().map(UUID::toString).toList()));
        if (!q.types().isEmpty()) filters.add(terms("type", q.types()));
        if (!q.extensions().isEmpty()) filters.add(terms("extension", q.extensions()));
        if (!q.sites().isEmpty()) filters.add(terms("site", q.sites()));
        if (!q.authors().isEmpty()) filters.add(terms("author.raw", q.authors()));
        if (q.authorText() != null) {
            filters.add(Map.of("match", Map.of("author", Map.of("query", q.authorText(), "operator", "and"))));
        }
        if (q.modifiedAfter() != null) {
            filters.add(Map.of("range", Map.of("modified", Map.of("gte", q.modifiedAfter().toEpochMilli()))));
        }
        Map<String, Object> must = Map.of("match_all", Map.of());
        if (q.text() != null) {
            List<Object> should = new ArrayList<>();
            should.add(Map.of("simple_query_string", Map.of("query", q.text(), "fields", FIELDS,
                    "default_operator", "and", "flags", FLAGS)));
            if (q.nameFragment() != null) {
                should.add(Map.of("match", Map.of("name_ngram",
                        Map.of("query", q.nameFragment(), "operator", "and", "boost", 0.5))));
            }
            must = Map.of("bool", Map.of("should", should, "minimum_should_match", 1));
        }
        return Map.of("bool", Map.of("must", List.of(must), "filter", filters));
    }

    private static Map<String, Object> terms(String field, Collection<String> values) {
        return Map.of("terms", Map.of(field, values));
    }

    private static Map<String, Object> aggregations() {
        Map<String, Object> aggs = new LinkedHashMap<>();
        aggs.put(FACET_TYPE, Map.of("terms", Map.of("field", "type", "size", 20)));
        aggs.put(FACET_SITE, Map.of("terms", Map.of("field", "site", "size", 20)));
        aggs.put(FACET_AUTHOR, Map.of("terms", Map.of("field", "author.raw", "size", 20)));
        Instant now = Instant.now();
        List<Object> ranges = new ArrayList<>();
        SearchQuery.MODIFIED_RANGES.forEach((key, range) ->
                ranges.add(Map.of("key", key, "from", now.minus(range).toEpochMilli())));
        aggs.put(FACET_MODIFIED, Map.of("date_range", Map.of("field", "modified", "ranges", ranges)));
        return aggs;
    }

    private static Map<String, List<Facet>> facets(JsonNode aggs) {
        Map<String, List<Facet>> out = new LinkedHashMap<>();
        for (String name : List.of(FACET_TYPE, FACET_SITE, FACET_AUTHOR)) {
            List<Facet> values = new ArrayList<>();
            for (JsonNode b : aggs.path(name).path("buckets")) values.add(new Facet(b.path("key").asText(), b.path("doc_count").asLong()));
            out.put(name, values);
        }
        Map<String, Long> modified = new HashMap<>();
        for (JsonNode b : aggs.path(FACET_MODIFIED).path("buckets")) {
            modified.put(b.path("key").asText(), b.path("doc_count").asLong());
        }
        List<Facet> ranges = new ArrayList<>();
        for (String key : SearchQuery.MODIFIED_RANGES.keySet()) {
            long n = modified.getOrDefault(key, 0L);
            if (n > 0) ranges.add(new Facet(key, n));
        }
        out.put(FACET_MODIFIED, ranges);
        return out;
    }

    @Override
    public long count() {
        return read(send("GET", "/" + index + "/_count", null, null)).path("count").asLong();
    }

    @Override
    public void refresh() {
        send("POST", "/" + index + "/_refresh", null, null);
    }

    @Override
    public void reset() {
        send("DELETE", "/" + index, null, null, 404);
        ensureIndex();
    }

    @Override
    public String status() {
        JsonNode health = read(send("GET", "/_cluster/health", null, null));
        return "cluster " + health.path("status").asText() + ", " + count() + " documents";
    }

    private String write(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (IOException e) {
            throw new UnavailableException("Could not write an OpenSearch request", e);
        }
    }

    private JsonNode read(HttpResponse<String> response) {
        try {
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new UnavailableException("OpenSearch sent an unreadable response", e);
        }
    }

    /** @param allowed error statuses the caller handles itself */
    private HttpResponse<String> send(String method, String path, String body, String contentType, int... allowed) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (contentType != null) b.header("Content-Type", contentType);
        if (authorization != null) b.header("Authorization", authorization);
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = r.statusCode();
            if (status >= 300 && Arrays.stream(allowed).noneMatch(a -> a == status)) {
                String text = r.body();
                throw new UnavailableException("OpenSearch answered " + status + " to " + method + " " + path + ": "
                        + text.substring(0, Math.min(text.length(), 300)));
            }
            return r;
        } catch (IOException e) {
            throw new UnavailableException("OpenSearch cannot be reached at " + base, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("Interrupted while calling OpenSearch", e);
        }
    }
}
