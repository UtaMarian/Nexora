package io.nexora.admin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the most recent server log lines in memory so administrators can read them in Central Administration
 * without shell access. The regular console/file logging is unchanged.
 */
@Component
public class LogBuffer extends AppenderBase<ILoggingEvent> {
    public static final int CAPACITY = 5000;

    public record Entry(long seq, Instant time, String level, String logger, String thread, String message, String error) {}

    private final Deque<Entry> entries = new ArrayDeque<>(CAPACITY);
    private long seq;

    @PostConstruct
    void install() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) return;
        setContext(ctx);
        setName("nexora-admin-buffer");
        start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(this);
    }

    @PreDestroy
    void uninstall() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) {
            ctx.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(this);
        }
        stop();
    }

    @Override
    protected synchronized void append(ILoggingEvent e) {
        if (entries.size() >= CAPACITY) entries.removeFirst();
        entries.addLast(new Entry(++seq, Instant.ofEpochMilli(e.getTimeStamp()), e.getLevel().toString(), e.getLoggerName(),
                e.getThreadName(), e.getFormattedMessage(), error(e.getThrowableProxy())));
    }

    /** Newest first, filtered by minimum level and text (in the message, logger or error). */
    public synchronized List<Entry> query(String minLevel, String text, int limit) {
        Level min = Level.toLevel(minLevel == null ? "INFO" : minLevel, Level.INFO);
        String q = text == null || text.isBlank() ? null : text.toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        var it = entries.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            Entry e = it.next();
            if (!Level.toLevel(e.level()).isGreaterOrEqual(min)) continue;
            if (q != null && !(contains(e.message(), q) || contains(e.logger(), q) || contains(e.error(), q))) continue;
            out.add(e);
        }
        return out;
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }

    private static String error(IThrowableProxy t) {
        if (t == null) return null;
        StringBuilder b = new StringBuilder();
        int depth = 0;
        while (t != null && depth++ < 4) {
            b.append(depth > 1 ? "Caused by: " : "").append(t.getClassName()).append(": ").append(t.getMessage()).append('\n');
            StackTraceElementProxy[] frames = t.getStackTraceElementProxyArray();
            for (int i = 0; i < Math.min(frames.length, 8); i++) b.append("    at ").append(frames[i].getStackTraceElement()).append('\n');
            t = t.getCause();
        }
        return b.toString();
    }
}
