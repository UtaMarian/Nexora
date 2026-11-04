package io.nexora.search;

import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.identity.CurrentUser;
import io.nexora.search.SearchQueueEntry.Kind;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** What Central Administration sees and does with search. */
@Service
public class SearchAdminService {
    private static final List<SearchText.Status> UNREAD = List.of(SearchText.Status.REJECTED, SearchText.Status.FAILED);

    public record Component(String name, boolean ok, String detail) {}

    public record Status(Component engine, Component extractor, long indexing, long reading,
                         Map<SearchText.Status, Long> documents, List<SearchTextRepository.Unread> unread) {}

    private final SearchEngine engine;
    private final TextExtractor extractor;
    private final SearchIndexer indexer;
    private final SearchQueue queue;
    private final SearchQueueRepository entries;
    private final SearchTextRepository texts;
    private final AuditService audit;

    public SearchAdminService(SearchEngine engine, TextExtractor extractor, SearchIndexer indexer, SearchQueue queue,
                              SearchQueueRepository entries, SearchTextRepository texts, AuditService audit) {
        this.engine = engine;
        this.extractor = extractor;
        this.indexer = indexer;
        this.queue = queue;
        this.entries = entries;
        this.texts = texts;
        this.audit = audit;
    }

    public Component engine() {
        try {
            return new Component(engine.name(), true, engine.status());
        } catch (RuntimeException e) {
            return new Component(engine.name(), false, e.getMessage());
        }
    }

    public Component extractor() {
        try {
            return new Component(extractor.name(), true, extractor.status());
        } catch (Exception e) {
            return new Component(extractor.name(), false, e.getMessage());
        }
    }

    public Status status() {
        Map<SearchText.Status, Long> documents = new EnumMap<>(SearchText.Status.class);
        for (SearchText.Status s : SearchText.Status.values()) documents.put(s, 0L);
        for (Object[] row : texts.countByStatus()) documents.put((SearchText.Status) row[0], (Long) row[1]);
        return new Status(engine(), extractor(), entries.countByKindIn(List.of(Kind.NODE, Kind.SUBTREE, Kind.PAGE)),
                entries.countByKindIn(List.of(Kind.TEXT)), documents, texts.unread(UNREAD, PageRequest.of(0, 50)));
    }

    /** Empties the index and fills it again from the database. Search shows partial results until that finishes. */
    public void rebuild(CurrentUser actor) {
        try {
            indexer.rebuild();
        } catch (SearchEngine.UnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "search_unavailable", e.getMessage());
        }
        audit.record(actor, "SEARCH_REBUILD", null);
    }

    /** Reads every document that could not be read before, for example after OCR languages changed. */
    public void retryUnread(CurrentUser actor) {
        texts.deleteByStatusIn(UNREAD);
        queue.everything();
        audit.record(actor, "SEARCH_RETRY_UNREAD", null);
    }
}
