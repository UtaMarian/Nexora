package io.nexora.search;

import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishPossessiveFilter;
import org.apache.lucene.analysis.en.PorterStemFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.simple.SimpleQueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * The embedded engine, for development and small single-server installs. It uses the same analysis and query syntax
 * as {@link OpenSearchEngine}. Facets are counted from the first {@value #FACET_WINDOW} matches, and name fragments
 * use a wildcard scan, which suits a small index only.
 */
@Component
@ConditionalOnProperty(name = "nexora.search.engine", havingValue = "lucene", matchIfMissing = true)
public class LuceneSearchEngine implements SearchEngine {
    private static final int FACET_WINDOW = 10_000;
    private static final Map<String, Float> FIELDS = Map.of(
            "title", 5f, "name_text", 4f, "description", 2f, "meta", 2f, "author", 1f, "content", 1f);
    private static final List<String> SNIPPET_FIELDS = List.of("content", "description", "meta");
    private static final FieldType HIGHLIGHTED = highlighted();

    private final Directory directory;
    private final Analyzer analyzer = new NexoraAnalyzer();
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private boolean created;

    public LuceneSearchEngine(SearchProperties properties) throws IOException {
        String path = properties.lucene().path();
        if (path == null || path.isBlank()) {
            directory = new ByteBuffersDirectory();
        } else {
            directory = FSDirectory.open(Files.createDirectories(Path.of(path)));
        }
        created = !DirectoryReader.indexExists(directory);
        writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
        writer.commit();
        searchers = new SearcherManager(writer, null);
    }

    private static FieldType highlighted() {
        FieldType t = new FieldType(TextField.TYPE_STORED);
        t.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        t.freeze();
        return t;
    }

    /** No stop words, so phrases match exactly as written; matches OpenSearch's {@code nexora_text}. */
    private static final class NexoraAnalyzer extends Analyzer {
        @Override
        protected TokenStreamComponents createComponents(String field) {
            StandardTokenizer source = new StandardTokenizer();
            TokenStream s = new EnglishPossessiveFilter(source);
            s = new LowerCaseFilter(s);
            s = new ASCIIFoldingFilter(s);
            return new TokenStreamComponents(source, new PorterStemFilter(s));
        }

        @Override
        protected TokenStream normalize(String field, TokenStream in) {
            return new ASCIIFoldingFilter(new LowerCaseFilter(in));
        }
    }

    @Override
    public String name() {
        return "Lucene (embedded)";
    }

    @Override
    public synchronized boolean ensureIndex() {
        boolean was = created;
        created = false;
        return was;
    }

    @Override
    public void index(List<SearchDocument> documents) {
        if (documents.isEmpty()) return;
        try {
            for (SearchDocument d : documents) writer.updateDocument(new Term("id", d.id().toString()), toLucene(d));
            commit();
        } catch (IOException e) {
            throw new UnavailableException("Could not write to the search index", e);
        }
    }

    private static Document toLucene(SearchDocument d) {
        Document doc = new Document();
        doc.add(new StringField("id", d.id().toString(), Field.Store.YES));
        keyword(doc, "type", d.type());
        keyword(doc, "extension", d.extension());
        keyword(doc, "site", d.site());
        keyword(doc, "site_id", d.siteId().toString());
        keyword(doc, "acl_source", d.aclSource().toString());
        keyword(doc, "author_raw", d.author());
        keyword(doc, "name_lower", d.name() == null ? null : d.name().toLowerCase(Locale.ROOT));
        text(doc, "title", d.title());
        text(doc, "name_text", d.nameText());
        text(doc, "author", d.author());
        highlighted(doc, "description", d.description());
        highlighted(doc, "meta", d.meta());
        highlighted(doc, "content", d.content());
        long modified = d.modifiedAt().toEpochMilli();
        doc.add(new LongPoint("modified", modified));
        doc.add(new NumericDocValuesField("modified", modified));
        doc.add(new StoredField("modified_at", modified));
        return doc;
    }

    private static void keyword(Document doc, String field, String value) {
        if (value != null && !value.isEmpty()) doc.add(new StringField(field, value, Field.Store.YES));
    }

    private static void text(Document doc, String field, String value) {
        if (value != null && !value.isEmpty()) doc.add(new TextField(field, value, Field.Store.NO));
    }

    private static void highlighted(Document doc, String field, String value) {
        if (value != null && !value.isEmpty()) doc.add(new Field(field, value, HIGHLIGHTED));
    }

    @Override
    public void delete(Collection<UUID> ids) {
        if (ids.isEmpty()) return;
        try {
            writer.deleteDocuments(ids.stream().map(id -> new Term("id", id.toString())).toArray(Term[]::new));
            commit();
        } catch (IOException e) {
            throw new UnavailableException("Could not write to the search index", e);
        }
    }

    @Override
    public void deleteSite(UUID siteId) {
        try {
            writer.deleteDocuments(new Term("site_id", siteId.toString()));
            commit();
        } catch (IOException e) {
            throw new UnavailableException("Could not write to the search index", e);
        }
    }

    private void commit() throws IOException {
        writer.commit();
        searchers.maybeRefreshBlocking();
    }

    @Override
    public Results search(SearchQuery q, Set<UUID> aclSources) {
        try {
            Query text = q.text() == null ? null : text(q);
            Query query = compose(q, text, aclSources);
            IndexSearcher searcher = searchers.acquire();
            try {
                long total = searcher.count(query);
                int wanted = Math.max(1, q.from() + q.size());
                TopDocs top = q.sort() == SearchQuery.Sort.MODIFIED
                        ? searcher.search(query, wanted, new Sort(new SortField("modified", SortField.Type.LONG, true)))
                        : searcher.search(query, wanted);
                ScoreDoc[] all = top.scoreDocs;
                ScoreDoc[] page = q.from() >= all.length ? new ScoreDoc[0]
                        : Arrays.copyOfRange(all, q.from(), all.length);
                String[] snippets = snippets(searcher, text, page);
                StoredFields stored = searcher.storedFields();
                List<Hit> hits = new ArrayList<>();
                for (int i = 0; i < page.length; i++) {
                    hits.add(new Hit(UUID.fromString(stored.document(page[i].doc, Set.of("id")).get("id")), snippets[i]));
                }
                return new Results(total, hits, facets(searcher, query));
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            throw new UnavailableException("Could not read the search index", e);
        }
    }

    private Query text(SearchQuery q) {
        SimpleQueryParser parser = new SimpleQueryParser(analyzer, FIELDS);
        parser.setDefaultOperator(BooleanClause.Occur.MUST);
        Query parsed = parser.parse(q.text());
        if (q.nameFragment() == null) return parsed;
        return new BooleanQuery.Builder()
                .add(parsed, BooleanClause.Occur.SHOULD)
                .add(new WildcardQuery(new Term("name_lower", "*" + q.nameFragment() + "*")), BooleanClause.Occur.SHOULD)
                .build();
    }

    private Query compose(SearchQuery q, Query text, Set<UUID> aclSources) {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        b.add(text == null ? new MatchAllDocsQuery() : text, BooleanClause.Occur.MUST);
        if (aclSources != null) filter(b, "acl_source", aclSources.stream().map(UUID::toString).toList());
        if (!q.types().isEmpty()) filter(b, "type", q.types());
        if (!q.extensions().isEmpty()) filter(b, "extension", q.extensions());
        if (!q.sites().isEmpty()) filter(b, "site", q.sites());
        if (!q.authors().isEmpty()) filter(b, "author_raw", q.authors());
        if (q.authorText() != null) {
            SimpleQueryParser parser = new SimpleQueryParser(analyzer, "author");
            parser.setDefaultOperator(BooleanClause.Occur.MUST);
            b.add(parser.parse(q.authorText()), BooleanClause.Occur.FILTER);
        }
        if (q.modifiedAfter() != null) {
            b.add(LongPoint.newRangeQuery("modified", q.modifiedAfter().toEpochMilli(), Long.MAX_VALUE),
                    BooleanClause.Occur.FILTER);
        }
        return b.build();
    }

    private static void filter(BooleanQuery.Builder b, String field, Collection<String> values) {
        List<BytesRef> terms = values.stream().map(BytesRef::new).toList();
        b.add(new TermInSetQuery(field, terms), BooleanClause.Occur.FILTER);
    }

    private String[] snippets(IndexSearcher searcher, Query text, ScoreDoc[] page) throws IOException {
        String[] out = new String[page.length];
        if (text == null || page.length == 0) return out;
        UnifiedHighlighter highlighter = UnifiedHighlighter.builder(searcher, analyzer)
                .withFormatter(new DefaultPassageFormatter(MATCH_START, MATCH_END, " … ", false))
                .withMaxLength(Integer.MAX_VALUE - 1)
                .build();
        TopDocs slice = new TopDocs(new TotalHits(page.length, TotalHits.Relation.EQUAL_TO), page);
        for (String field : SNIPPET_FIELDS) {
            String[] found = highlighter.highlight(field, text, slice, 1);
            for (int i = 0; i < out.length; i++) if (out[i] == null) out[i] = found[i];
        }
        return out;
    }

    private Map<String, List<Facet>> facets(IndexSearcher searcher, Query query) throws IOException {
        Map<String, Map<String, Long>> counts = new HashMap<>();
        Map<String, Long> modified = new LinkedHashMap<>();
        Map<String, Instant> cutoffs = new LinkedHashMap<>();
        Instant now = Instant.now();
        SearchQuery.MODIFIED_RANGES.forEach((key, range) -> {
            modified.put(key, 0L);
            cutoffs.put(key, now.minus(range));
        });
        StoredFields stored = searcher.storedFields();
        Set<String> fields = Set.of("type", "site", "author_raw", "modified_at");
        for (ScoreDoc sd : searcher.search(query, FACET_WINDOW).scoreDocs) {
            Document d = stored.document(sd.doc, fields);
            count(counts, FACET_TYPE, d.get("type"));
            count(counts, FACET_SITE, d.get("site"));
            count(counts, FACET_AUTHOR, d.get("author_raw"));
            Instant at = Instant.ofEpochMilli(d.getField("modified_at").numericValue().longValue());
            cutoffs.forEach((key, cutoff) -> {
                if (at.isAfter(cutoff)) modified.merge(key, 1L, Long::sum);
            });
        }
        Map<String, List<Facet>> out = new LinkedHashMap<>();
        for (String facet : List.of(FACET_TYPE, FACET_SITE, FACET_AUTHOR)) {
            out.put(facet, counts.getOrDefault(facet, Map.of()).entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(20).map(e -> new Facet(e.getKey(), e.getValue())).toList());
        }
        out.put(FACET_MODIFIED, modified.entrySet().stream().filter(e -> e.getValue() > 0)
                .map(e -> new Facet(e.getKey(), e.getValue())).toList());
        return out;
    }

    private static void count(Map<String, Map<String, Long>> counts, String facet, String value) {
        if (value != null) counts.computeIfAbsent(facet, k -> new HashMap<>()).merge(value, 1L, Long::sum);
    }

    @Override
    public long count() {
        try {
            IndexSearcher searcher = searchers.acquire();
            try {
                return searcher.getIndexReader().numDocs();
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            throw new UnavailableException("Could not read the search index", e);
        }
    }

    @Override
    public void refresh() {
        try {
            searchers.maybeRefreshBlocking();
        } catch (IOException e) {
            throw new UnavailableException("Could not read the search index", e);
        }
    }

    @Override
    public void reset() {
        try {
            writer.deleteAll();
            commit();
        } catch (IOException e) {
            throw new UnavailableException("Could not write to the search index", e);
        }
    }

    @Override
    public String status() {
        return count() + " documents";
    }

    @PreDestroy
    void close() throws IOException {
        searchers.close();
        writer.close();
        directory.close();
    }
}
