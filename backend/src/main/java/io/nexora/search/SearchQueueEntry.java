package io.nexora.search;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** One unit of indexing work. See {@link SearchQueue}. */
@Entity
@Table(name = "search_queue")
public class SearchQueueEntry {

    public enum Kind {
        /** Index one node as it is now, or remove it if it is gone. */
        NODE,
        /** Queue a node and everything under it. */
        SUBTREE,
        /** Index one page or news post. */
        PAGE,
        /** Read the text of a file's current version, then index it again. */
        TEXT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private UUID targetId;
    private int attempts;
    private Instant notBefore;

    protected SearchQueueEntry() {}

    public SearchQueueEntry(Kind kind, UUID targetId) {
        this.kind = kind;
        this.targetId = targetId;
        this.notBefore = dueNow();
    }

    /** Whole milliseconds: the check against the current time is no finer, so a new row must not look like the future. */
    static Instant dueNow() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public Long getId() { return id; }
    public Kind getKind() { return kind; }
    public UUID getTargetId() { return targetId; }
    public int getAttempts() { return attempts; }

    public void retryAfter(Duration delay, boolean countAttempt) {
        if (countAttempt) attempts++;
        notBefore = Instant.now().plus(delay);
    }
}
