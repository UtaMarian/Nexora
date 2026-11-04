package io.nexora.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.nexora.search.SearchQuery.Filters;
import static org.assertj.core.api.Assertions.assertThat;

/** What every engine must do. Runs against Lucene always and against a real OpenSearch when one is available. */
abstract class SearchEngineContract {
    protected static final UUID ACL_HR = UUID.randomUUID();
    protected static final UUID ACL_IT = UUID.randomUUID();
    protected static final UUID SITE_HR = UUID.randomUUID();
    protected static final UUID SITE_IT = UUID.randomUUID();

    private final UUID policy = UUID.randomUUID();
    private final UUID budget = UUID.randomUUID();
    private final UUID contract = UUID.randomUUID();

    protected abstract SearchEngine engine();

    private static SearchDocument doc(UUID id, String type, String name, String content, String site, UUID siteId,
                                      UUID acl, String author, Instant at) {
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        return new SearchDocument(id, type, name, name, SearchDocumentBuilder.words(name), "", "", content,
                "/sites/" + site + "/" + name, site, siteId, ext, author, at, 10, acl);
    }

    @BeforeEach
    void fill() {
        engine().ensureIndex();
        engine().reset();
        engine().index(List.of(
                doc(policy, "word", "Leave.docx", "Employees receive annual leave policies. Învățământul continuă în țară.",
                        "hr", SITE_HR, ACL_HR, "Ana Pop", Instant.now()),
                doc(budget, "excel", "budget2024_final.xlsx", "Quarterly numbers for the zebrafish project",
                        "it", SITE_IT, ACL_IT, "Dan Ionescu", Instant.now().minus(Duration.ofDays(3))),
                doc(contract, "pdf", "Contract.pdf", "The zebrafish supply agreement, signed by the board",
                        "hr", SITE_HR, ACL_HR, "Ana Pop", Instant.now().minus(Duration.ofDays(100)))));
        engine().refresh();
    }

    private SearchEngine.Results run(String q) {
        return engine().search(SearchQuery.parse(q, Filters.NONE, SearchQuery.Sort.RELEVANCE, 0, 20), null);
    }

    private List<UUID> ids(String q) {
        return run(q).hits().stream().map(SearchEngine.Hit::id).toList();
    }

    @Test
    void findsWordsInContentWithHighlightedSnippets() {
        SearchEngine.Results r = run("agreement");
        assertThat(r.total()).isEqualTo(1);
        assertThat(r.hits().get(0).id()).isEqualTo(contract);
        assertThat(r.hits().get(0).snippet()).contains(SearchEngine.MATCH_START + "agreement" + SearchEngine.MATCH_END);
    }

    @Test
    void stemsWordsAndIgnoresCaseAndDiacritics() {
        assertThat(ids("POLICY")).containsExactly(policy);
        assertThat(ids("invatamantul")).containsExactly(policy);
        assertThat(ids("tara")).containsExactly(policy);
    }

    @Test
    void understandsQuerySyntaxAndNeverFailsOnIt() {
        assertThat(ids("zebrafish agreement")).containsExactly(contract);
        assertThat(ids("\"supply agreement\"")).containsExactly(contract);
        assertThat(ids("\"agreement supply\"")).isEmpty();
        assertThat(ids("zebrafish NOT agreement")).containsExactly(budget);
        assertThat(ids("leave OR agreement")).containsExactlyInAnyOrder(policy, contract);
        assertThat(ids("zebraf*")).containsExactlyInAnyOrder(budget, contract);
        for (String nasty : List.of("\"unbalanced", "((", "a AND", "*", "||", "foo:bar", "\\", "-")) run(nasty);
    }

    @Test
    void matchesAnyPartOfAFileName() {
        assertThat(ids("budget")).containsExactly(budget);
        assertThat(ids("final")).containsExactly(budget);
        assertThat(ids("get2024")).containsExactly(budget);
    }

    @Test
    void propertyFiltersWork() {
        assertThat(ids("zebrafish filetype:pdf")).containsExactly(contract);
        assertThat(ids("zebrafish site:it")).containsExactly(budget);
        assertThat(ids("zebrafish author:ionescu")).containsExactly(budget);
        assertThat(ids("zebrafish type:excel")).containsExactly(budget);
        assertThat(ids("* filetype:docx")).containsExactly(policy);
    }

    @Test
    void onlyDocumentsUnderReadableAclsMatch() {
        SearchQuery q = SearchQuery.parse("zebrafish", Filters.NONE, SearchQuery.Sort.RELEVANCE, 0, 20);
        SearchEngine.Results r = engine().search(q, Set.of(ACL_IT));
        assertThat(r.total()).isEqualTo(1);
        assertThat(r.hits().get(0).id()).isEqualTo(budget);
        assertThat(r.facets().get(SearchEngine.FACET_SITE)).containsExactly(new SearchEngine.Facet("it", 1));
        assertThat(engine().search(q, Set.of(UUID.randomUUID())).total()).isZero();
    }

    @Test
    void countsFacets() {
        Map<String, List<SearchEngine.Facet>> f = run("zebrafish").facets();
        assertThat(f.get(SearchEngine.FACET_TYPE)).containsExactlyInAnyOrder(
                new SearchEngine.Facet("excel", 1), new SearchEngine.Facet("pdf", 1));
        assertThat(f.get(SearchEngine.FACET_AUTHOR)).containsExactlyInAnyOrder(
                new SearchEngine.Facet("Ana Pop", 1), new SearchEngine.Facet("Dan Ionescu", 1));
        assertThat(f.get(SearchEngine.FACET_MODIFIED)).containsExactly(new SearchEngine.Facet("week", 1),
                new SearchEngine.Facet("month", 1), new SearchEngine.Facet("year", 2));
    }

    @Test
    void filtersFromFacetsAndSorting() {
        SearchEngine.Results recent = engine().search(SearchQuery.parse("zebrafish",
                new Filters(List.of(), List.of(), List.of(), "week"), SearchQuery.Sort.RELEVANCE, 0, 20), null);
        assertThat(recent.hits()).extracting(SearchEngine.Hit::id).containsExactly(budget);

        SearchEngine.Results byAuthor = engine().search(SearchQuery.parse("zebrafish",
                new Filters(List.of(), List.of(), List.of("Ana Pop"), null), SearchQuery.Sort.RELEVANCE, 0, 20), null);
        assertThat(byAuthor.hits()).extracting(SearchEngine.Hit::id).containsExactly(contract);

        SearchEngine.Results newest = engine().search(SearchQuery.parse("*",
                new Filters(List.of("word", "pdf"), List.of(), List.of(), null), SearchQuery.Sort.MODIFIED, 0, 20), null);
        assertThat(newest.hits()).extracting(SearchEngine.Hit::id).containsExactly(policy, contract);
    }

    @Test
    void pagesThroughResults() {
        SearchQuery first = SearchQuery.parse("zebrafish", Filters.NONE, SearchQuery.Sort.MODIFIED, 0, 1);
        SearchQuery second = SearchQuery.parse("zebrafish", Filters.NONE, SearchQuery.Sort.MODIFIED, 1, 1);
        assertThat(engine().search(first, null).hits()).extracting(SearchEngine.Hit::id).containsExactly(budget);
        SearchEngine.Results r = engine().search(second, null);
        assertThat(r.hits()).extracting(SearchEngine.Hit::id).containsExactly(contract);
        assertThat(r.total()).isEqualTo(2);
    }

    @Test
    void replacesAndRemovesDocuments() {
        engine().index(List.of(doc(budget, "excel", "budget2024_final.xlsx", "Now about marmots", "it", SITE_IT, ACL_IT,
                "Dan Ionescu", Instant.now())));
        engine().refresh();
        assertThat(ids("zebrafish")).containsExactly(contract);
        assertThat(ids("marmots")).containsExactly(budget);

        engine().delete(List.of(contract));
        engine().refresh();
        assertThat(ids("zebrafish")).isEmpty();
        assertThat(engine().count()).isEqualTo(2);

        engine().deleteSite(SITE_HR);
        engine().refresh();
        assertThat(engine().count()).isEqualTo(1);
    }
}
