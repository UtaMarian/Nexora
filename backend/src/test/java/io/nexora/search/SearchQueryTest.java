package io.nexora.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.nexora.search.SearchQuery.Filters;
import static org.assertj.core.api.Assertions.assertThat;

class SearchQueryTest {

    private static SearchQuery parse(String q) {
        return SearchQuery.parse(q, Filters.NONE, SearchQuery.Sort.RELEVANCE, 0, 20);
    }

    @Test
    void pullsPropertiesOutOfTheText() {
        SearchQuery q = parse("annual report filetype:.PDF site:HR author:\"Ana Pop\" type:word");
        assertThat(q.text()).isEqualTo("annual report");
        assertThat(q.extensions()).containsExactly("pdf");
        assertThat(q.sites()).containsExactly("hr");
        assertThat(q.authorText()).isEqualTo("Ana Pop");
        assertThat(q.types()).containsExactly("word");
    }

    @Test
    void leavesOrdinaryColonsAlone() {
        assertThat(parse("Note: budget").text()).isEqualTo("Note: budget");
        assertThat(parse("http://intranet/site:x").text()).isEqualTo("http://intranet/site:x");
    }

    @Test
    void translatesOperatorsOutsidePhrasesOnly() {
        assertThat(parse("cats AND dogs").text()).isEqualTo("cats + dogs");
        assertThat(parse("cats OR dogs").text()).isEqualTo("cats | dogs");
        assertThat(parse("cats NOT dogs").text()).isEqualTo("cats -dogs");
        assertThat(parse("\"cats OR dogs\" NOT fish").text()).isEqualTo("\"cats OR dogs\" -fish");
        assertThat(parse("ORDER AND").text()).isEqualTo("ORDER AND");
    }

    @Test
    void onlySingleBareWordsAlsoMatchInsideNames() {
        assertThat(parse("Budget2024").nameFragment()).isEqualTo("budget2024");
        assertThat(parse("two words").nameFragment()).isNull();
        assertThat(parse("\"quoted\"").nameFragment()).isNull();
        assertThat(parse("ab").nameFragment()).isNull();
    }

    @Test
    void knowsWhenThereIsNothingToSearchFor() {
        assertThat(parse("  ").isEmpty()).isTrue();
        assertThat(parse("*").isEmpty()).isTrue();
        assertThat(parse("* filetype:pdf").isEmpty()).isFalse();
        assertThat(SearchQuery.parse("", new Filters(List.of("pdf"), List.of(), List.of(), null),
                SearchQuery.Sort.RELEVANCE, 0, 20).isEmpty()).isFalse();
    }

    @Test
    void cleansTextForStorage() {
        assertThat(TextExtractor.clean("a\u0000b\n\n  c\uD800de")).isEqualTo("a b c d e");
        assertThat(TextExtractor.clean("emoji 😀 stays")).isEqualTo("emoji 😀 stays");
    }

    @Test
    void splitsSnippetsWithoutHtml() {
        String marked = "before <b> " + SearchEngine.MATCH_START + "hit" + SearchEngine.MATCH_END + " after";
        assertThat(SearchService.segments(marked)).containsExactly(new SearchService.Segment("before <b> ", false),
                new SearchService.Segment("hit", true), new SearchService.Segment(" after", false));
        assertThat(SearchService.segments(null)).isEmpty();

        String longText = "word ".repeat(200) + SearchEngine.MATCH_START + "hit" + SearchEngine.MATCH_END + " tail".repeat(200);
        List<SearchService.Segment> clipped = SearchService.segments(longText);
        assertThat(clipped).contains(new SearchService.Segment("hit", true));
        assertThat(clipped.get(0).text()).isEqualTo("… ");
        assertThat(clipped.get(clipped.size() - 1).text()).isEqualTo(" …");
        assertThat(clipped.stream().mapToInt(s -> s.text().length()).sum()).isLessThan(260);
    }

    @Test
    void splitsFileNamesIntoWords() {
        assertThat(SearchDocumentBuilder.words("Q3_budgetReport-2024.xlsx")).isEqualTo("Q3 budget Report 2024 xlsx");
    }
}
