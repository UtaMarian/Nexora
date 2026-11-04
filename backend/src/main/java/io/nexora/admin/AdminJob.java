package io.nexora.admin;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A background job started from Central Administration: backup, restore, import or migration. */
@Entity
@Table(name = "admin_job")
public class AdminJob {

    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;
        public boolean isFinished() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }
    }

    public static final int MAX_LOG = 190_000;

    @Id
    private UUID id;
    private String type;
    private String title;
    @Enumerated(EnumType.STRING)
    private Status status;
    private int progress;
    private String message;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> params;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> result;
    private String log;
    private UUID createdBy;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    protected AdminJob() {}

    public AdminJob(String type, String title, Map<String, Object> params, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.title = title;
        this.params = params;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.status = Status.QUEUED;
        this.log = "";
    }

    public void start() {
        status = Status.RUNNING;
        startedAt = Instant.now();
    }

    public void finish(Status s, String message, Map<String, Object> result) {
        status = s;
        this.message = message == null ? null : message.length() > 1000 ? message.substring(0, 1000) : message;
        this.result = result;
        if (s == Status.SUCCEEDED) progress = 100;
        finishedAt = Instant.now();
    }

    public void appendLog(String lines) {
        String next = (log == null ? "" : log) + lines;
        if (next.length() > MAX_LOG) next = "… (earlier lines trimmed)\n" + next.substring(next.length() - MAX_LOG + 40);
        log = next;
    }

    public void setProgress(int progress) { this.progress = Math.max(0, Math.min(100, progress)); }
    public void setMessage(String message) { this.message = message; }

    public UUID getId() { return id; }
    public String getType() { return type; }
    public String getTitle() { return title; }
    public Status getStatus() { return status; }
    public int getProgress() { return progress; }
    public String getMessage() { return message; }
    public Map<String, Object> getParams() { return params; }
    public Map<String, Object> getResult() { return result; }
    public String getLog() { return log; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
