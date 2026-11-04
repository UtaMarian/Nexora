package io.nexora.admin;

import io.nexora.common.ApiException;
import io.nexora.identity.CurrentUser;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs long administrative work (backups, restores, migrations) in the background, two at a time, and keeps
 * its status, progress and log in {@code admin_job} so the admin pages can follow it.
 */
@Service
public class JobService {
    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    /** The work a job does. Returns a small result summary shown with the job. */
    @FunctionalInterface
    public interface Work {
        Map<String, Object> run(Context ctx) throws Exception;
    }

    public static class CancelledException extends RuntimeException {
        CancelledException() { super("Cancelled"); }
    }

    /** What running work can report back. */
    public interface Context {
        UUID jobId();
        CurrentUser actor();
        void log(String line);
        void progress(int percent);
        /** Throws {@link CancelledException} when an administrator cancelled the job. */
        void checkCancelled();
    }

    private final AdminJobRepository jobs;
    private final TransactionTemplate tx;
    private final ExecutorService pool;
    private final Set<UUID> cancelled = ConcurrentHashMap.newKeySet();

    public JobService(AdminJobRepository jobs, PlatformTransactionManager txManager) {
        this.jobs = jobs;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "nexora-job-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public AdminJob submit(String type, String title, Map<String, Object> params, CurrentUser actor, Work work) {
        AdminJob job = tx.execute(s -> jobs.save(new AdminJob(type, title, params == null ? Map.of() : params, actor.id())));
        pool.submit(() -> execute(job.getId(), actor, work));
        return job;
    }

    public List<AdminJob> latest(int limit) {
        return jobs.latest(PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    public AdminJob get(UUID id) {
        return jobs.findById(id).orElseThrow(() -> ApiException.notFound("Job"));
    }

    public void cancel(UUID id) {
        AdminJob job = get(id);
        if (job.getStatus().isFinished()) throw ApiException.conflict("job_finished", "The job has already finished");
        cancelled.add(id);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markInterrupted() {
        tx.executeWithoutResult(s -> jobs.findByStatusIn(List.of(AdminJob.Status.QUEUED, AdminJob.Status.RUNNING)).forEach(j -> {
            j.finish(AdminJob.Status.FAILED, "Interrupted by a server restart", null);
            jobs.save(j);
        }));
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }

    private void execute(UUID id, CurrentUser actor, Work work) {
        RunningContext ctx = new RunningContext(id, actor);
        update(id, j -> j.start());
        ctx.log("Started by " + actor.login());
        try {
            Map<String, Object> result = work.run(ctx);
            ctx.flush();
            update(id, j -> j.finish(AdminJob.Status.SUCCEEDED, "Completed", result));
        } catch (CancelledException e) {
            ctx.log("Cancelled by an administrator");
            ctx.flush();
            update(id, j -> j.finish(AdminJob.Status.CANCELLED, "Cancelled", null));
        } catch (Throwable e) {
            log.warn("Job {} failed", id, e);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            ctx.log("Failed: " + msg);
            ctx.flush();
            update(id, j -> j.finish(AdminJob.Status.FAILED, msg, null));
        } finally {
            cancelled.remove(id);
        }
    }

    private void update(UUID id, java.util.function.Consumer<AdminJob> change) {
        tx.executeWithoutResult(s -> jobs.findById(id).ifPresent(j -> {
            change.accept(j);
            jobs.save(j);
        }));
    }

    private final class RunningContext implements Context {
        private final UUID id;
        private final CurrentUser actor;
        private final StringBuilder pending = new StringBuilder();
        private long lastFlush = System.currentTimeMillis();
        private Integer pendingProgress;

        RunningContext(UUID id, CurrentUser actor) {
            this.id = id;
            this.actor = actor;
        }

        @Override public UUID jobId() { return id; }
        @Override public CurrentUser actor() { return actor; }

        @Override
        public synchronized void log(String line) {
            pending.append(TIME.format(Instant.now())).append("  ").append(line).append('\n');
            maybeFlush();
        }

        @Override
        public synchronized void progress(int percent) {
            pendingProgress = percent;
            maybeFlush();
        }

        @Override
        public void checkCancelled() {
            if (cancelled.contains(id)) throw new CancelledException();
        }

        private void maybeFlush() {
            if (System.currentTimeMillis() - lastFlush > 1500) flush();
        }

        synchronized void flush() {
            String lines = pending.toString();
            pending.setLength(0);
            Integer p = pendingProgress;
            pendingProgress = null;
            lastFlush = System.currentTimeMillis();
            if (lines.isEmpty() && p == null) return;
            update(id, j -> {
                if (!lines.isEmpty()) j.appendLog(lines);
                if (p != null) j.setProgress(p);
            });
        }
    }
}
