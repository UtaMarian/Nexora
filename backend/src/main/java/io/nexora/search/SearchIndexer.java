package io.nexora.search;

import io.nexora.content.FileVersion;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.pages.SitePage;
import io.nexora.pages.SitePageRepository;
import io.nexora.search.SearchQueueEntry.Kind;
import io.nexora.storage.BlobStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntSupplier;

/**
 * Works through {@code search_queue} in the background. It runs in two lanes so slow OCR never delays anything else:
 * the index lane puts a document in the index straight away, findable by name and columns, and the text lane reads
 * its contents and then has it indexed again with them.
 *
 * <p>While an administrator has search turned off, both lanes stop and changes wait in the queue.
 *
 * <p>Work is always "make the index match the database now", so repeating it is harmless. That is what makes retries
 * simple, and two servers overlapping would only waste effort.
 */
@Component
public class SearchIndexer {
    private static final Logger log = LoggerFactory.getLogger(SearchIndexer.class);
    private static final int BATCH = 200;
    /** Sent to the engine in pieces of about this many characters. */
    private static final int MAX_BULK_CHARS = 16_000_000;
    private static final int MAX_INDEX_ATTEMPTS = 5;
    private static final int MAX_TEXT_ATTEMPTS = 3;
    private static final Duration ENGINE_DOWN_DELAY = Duration.ofSeconds(30);
    private static final Duration EXTRACTOR_DOWN_DELAY = Duration.ofMinutes(1);

    private final SearchProperties properties;
    private final SearchEngine engine;
    private final SearchSwitch searchSwitch;
    private final TextExtractor extractor;
    private final SearchQueue queue;
    private final SearchQueueRepository entries;
    private final SearchTextRepository texts;
    private final SearchDocumentBuilder documents;
    private final NodeRepository nodes;
    private final SitePageRepository pages;
    private final BlobStore blobs;
    private final TransactionTemplate tx;
    private final ExecutorService readers;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;
    private volatile boolean indexReady;

    public SearchIndexer(SearchProperties properties, SearchEngine engine, SearchSwitch searchSwitch,
                         TextExtractor extractor, SearchQueue queue,
                         SearchQueueRepository entries, SearchTextRepository texts, SearchDocumentBuilder documents,
                         NodeRepository nodes, SitePageRepository pages, BlobStore blobs,
                         PlatformTransactionManager transactions) {
        this.properties = properties;
        this.engine = engine;
        this.searchSwitch = searchSwitch;
        this.extractor = extractor;
        this.queue = queue;
        this.entries = entries;
        this.texts = texts;
        this.documents = documents;
        this.nodes = nodes;
        this.pages = pages;
        this.blobs = blobs;
        this.tx = new TransactionTemplate(transactions);
        this.readers = Executors.newFixedThreadPool(Math.max(1, properties.extractThreads()), r -> {
            Thread t = new Thread(r, "nexora-search-reader");
            t.setDaemon(true);
            return t;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.workerEnabled()) return;
        running = true;
        workers.add(worker("nexora-search-index", this::indexStep));
        workers.add(worker("nexora-search-text", this::textStep));
        workers.forEach(Thread::start);
    }

    private Thread worker(String name, IntSupplier step) {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    if (step.getAsInt() == 0) Thread.sleep(properties.pollInterval().toMillis());
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    log.warn("Search indexing paused for {} s: {}", ENGINE_DOWN_DELAY.toSeconds(), e.getMessage());
                    try {
                        Thread.sleep(ENGINE_DOWN_DELAY.toMillis());
                    } catch (InterruptedException stop) {
                        return;
                    }
                }
            }
        }, name);
        t.setDaemon(true);
        return t;
    }

    @PreDestroy
    void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
        readers.shutdownNow();
    }

    /** Runs until no work is due. For tests and tools; the workers normally do this. */
    public void drain() {
        while (indexStep() + textStep() > 0) { /* more */ }
        engine.refresh();
    }

    /** Empties the index and queues everything again. Stored text is reused, so nothing is read or OCRed twice. */
    public synchronized void rebuild() {
        engine.reset();
        queue.everything();
    }

    // ---- index lane ----------------------------------------------------------------------------

    synchronized int indexStep() {
        if (!searchSwitch.enabled()) return 0;
        if (!indexReady) {
            if (engine.ensureIndex()) queue.everything();
            indexReady = true;
        }
        List<SearchQueueEntry> due = entries.due(List.of(Kind.NODE, Kind.SUBTREE, Kind.PAGE), Instant.now(), PageRequest.of(0, BATCH));
        if (due.isEmpty()) return 0;

        Map<UUID, List<SearchQueueEntry>> subtrees = new LinkedHashMap<>();
        Map<Kind, Map<UUID, List<SearchQueueEntry>>> targets = new EnumMap<>(Kind.class);
        for (SearchQueueEntry e : due) {
            Map<UUID, List<SearchQueueEntry>> group = e.getKind() == Kind.SUBTREE ? subtrees
                    : targets.computeIfAbsent(e.getKind(), k -> new LinkedHashMap<>());
            group.computeIfAbsent(e.getTargetId(), k -> new ArrayList<>()).add(e);
        }

        subtrees.forEach((id, rows) -> attempt(rows, () -> tx.executeWithoutResult(s -> {
            expand(id);
            remove(rows);
        })));

        Pending pending = new Pending();
        targets.forEach((kind, byTarget) -> byTarget.forEach((id, rows) -> {
            attempt(rows, () -> {
                tx.executeWithoutResult(s -> collect(id, kind, pending));
                pending.done.addAll(rows);
            });
            if (pending.chars >= MAX_BULK_CHARS) pending.flush();
        }));
        pending.flush();
        return due.size();
    }

    /** What has been gathered from the database and not yet sent to the engine. */
    private final class Pending {
        final List<SearchDocument> index = new ArrayList<>();
        final Set<UUID> delete = new HashSet<>();
        final Set<UUID> deleteSites = new HashSet<>();
        final Set<UUID> needText = new LinkedHashSet<>();
        final List<SearchQueueEntry> done = new ArrayList<>();
        long chars;

        void add(SearchDocument d) {
            index.add(d);
            chars += d.content().length();
        }

        /** Rows go only after the engine took the batch; if it is down they stay queued for the next try. */
        void flush() {
            engine.index(index);
            engine.delete(delete);
            deleteSites.forEach(engine::deleteSite);
            tx.executeWithoutResult(s -> {
                needText.forEach(queue::text);
                remove(done);
            });
            index.clear();
            delete.clear();
            deleteSites.clear();
            needText.clear();
            done.clear();
            chars = 0;
        }
    }

    private void remove(List<SearchQueueEntry> rows) {
        entries.deleteAllByIdInBatch(rows.stream().map(SearchQueueEntry::getId).toList());
    }

    private void expand(UUID id) {
        Node n = nodes.findById(id).orElse(null);
        if (n == null) {
            queue.node(id); // purged: this removes it, and its pages if it was a site
        } else if (n.getKind() == Node.Kind.SITE) {
            queue.wholeSite(id, n.getPath());
        } else if (n.isDeleted()) {
            queue.deletedWith(id);
        } else {
            queue.node(id);
            queue.liveBelow(n.getPath());
        }
    }

    private void collect(UUID id, Kind kind, Pending out) {
        if (kind == Kind.PAGE) collectPage(id, out);
        else collectNode(id, out);
    }

    private void collectNode(UUID id, Pending out) {
        Node n = nodes.findById(id).orElse(null);
        if (n == null) {
            out.delete.add(id);
            out.deleteSites.add(id);
            return;
        }
        if (n.isDeleted()) {
            out.delete.add(id);
            return;
        }
        if (documents.unpublished(n)) {
            out.delete.add(id);
            return;
        }
        String content = null;
        if (documents.wantsText(n)) {
            Optional<FileVersion> version = documents.currentVersion(n);
            Optional<SearchText> text = version.flatMap(v -> texts.findById(v.getStorageKey()));
            if (text.isPresent()) content = text.get().getBody();
            else if (version.isPresent()) out.needText.add(id);
        }
        out.add(documents.node(n, content));
    }

    private void collectPage(UUID id, Pending out) {
        SitePage page = pages.findById(id).orElse(null);
        Node site = page == null ? null : nodes.findLive(page.getSiteId()).orElse(null);
        if (site == null || !page.isPublished()) out.delete.add(id);
        else out.add(documents.page(page, site));
    }

    /** One bad document must not block the queue: it steps aside, and after a few tries it is dropped. */
    private void attempt(List<SearchQueueEntry> rows, Runnable work) {
        try {
            work.run();
        } catch (SearchEngine.UnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            SearchQueueEntry first = rows.get(0);
            log.warn("Could not index {} {}: {}", first.getKind(), first.getTargetId(), e.toString());
            tx.executeWithoutResult(s -> {
                if (first.getAttempts() + 1 >= MAX_INDEX_ATTEMPTS) {
                    log.error("Gave up indexing {} {}", first.getKind(), first.getTargetId());
                    remove(rows);
                } else {
                    Duration delay = Duration.ofMinutes(first.getAttempts() + 1L);
                    rows.forEach(r -> r.retryAfter(delay, true));
                    entries.saveAll(rows);
                }
            });
        }
    }

    // ---- text lane -----------------------------------------------------------------------------

    int textStep() {
        if (!searchSwitch.enabled()) return 0;
        int threads = Math.max(1, properties.extractThreads());
        List<SearchQueueEntry> due = entries.due(List.of(Kind.TEXT), Instant.now(), PageRequest.of(0, threads * 2));
        if (due.isEmpty()) return 0;
        Map<UUID, List<SearchQueueEntry>> byNode = new LinkedHashMap<>();
        due.forEach(e -> byNode.computeIfAbsent(e.getTargetId(), k -> new ArrayList<>()).add(e));
        List<Callable<Void>> jobs = new ArrayList<>();
        byNode.forEach((id, rows) -> jobs.add(() -> {
            read(id, rows);
            return null;
        }));
        try {
            readers.invokeAll(jobs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return due.size();
    }

    private record Job(String key, String mime, String extension) {}

    private void read(UUID id, List<SearchQueueEntry> rows) {
        Job job;
        try {
            job = tx.execute(s -> job(id));
            if (job == null) {
                finish(rows, null, false);
                return;
            }
            if (texts.existsById(job.key())) {
                finish(rows, null, true); // another document shares this blob
                return;
            }
        } catch (RuntimeException e) {
            log.warn("Could not prepare {} for reading: {}", id, e.toString());
            retry(rows, Duration.ofMinutes(1), true);
            return;
        }

        try (InputStream in = blobs.open(job.key())) {
            String text = TextExtractor.clean(extractor.extract(in, job.mime(), job.extension(), properties.maxTextChars()));
            store(rows, text.isEmpty() ? new SearchText(job.key(), SearchText.Status.EMPTY, null, null)
                    : new SearchText(job.key(), SearchText.Status.OK, text, null));
        } catch (TextExtractor.RejectedException e) {
            store(rows, new SearchText(job.key(), SearchText.Status.REJECTED, null, e.getMessage()));
        } catch (TextExtractor.UnavailableException e) {
            log.warn("Reading documents is paused: {}", e.getMessage());
            retry(rows, EXTRACTOR_DOWN_DELAY, false);
        } catch (IOException | RuntimeException e) {
            int attempt = rows.get(0).getAttempts() + 1;
            log.warn("Could not read {} (attempt {}): {}", id, attempt, e.toString());
            if (attempt >= MAX_TEXT_ATTEMPTS) {
                store(rows, new SearchText(job.key(), SearchText.Status.FAILED, null, e.toString()));
            } else {
                retry(rows, Duration.ofMinutes(attempt * 2L), true);
            }
        }
    }

    private Job job(UUID id) {
        Node n = nodes.findLive(id).orElse(null);
        if (n == null || !documents.wantsText(n)) return null;
        return documents.currentVersion(n)
                .map(v -> new Job(v.getStorageKey(), n.getMimeType(), SearchDocumentBuilder.extension(n.getName())))
                .orElse(null);
    }

    /** Files with identical content share a blob, so two readers can finish it together; either result will do. */
    private void store(List<SearchQueueEntry> rows, SearchText text) {
        try {
            finish(rows, text, true);
        } catch (DataIntegrityViolationException alreadyStored) {
            finish(rows, null, true);
        }
    }

    private void finish(List<SearchQueueEntry> rows, SearchText text, boolean reindex) {
        tx.executeWithoutResult(s -> {
            if (text != null) texts.save(text);
            if (reindex) queue.node(rows.get(0).getTargetId());
            remove(rows);
        });
    }

    private void retry(List<SearchQueueEntry> rows, Duration delay, boolean countAttempt) {
        tx.executeWithoutResult(s -> {
            rows.forEach(r -> r.retryAfter(delay, countAttempt));
            entries.saveAll(rows);
        });
    }
}
