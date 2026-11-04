package io.nexora.mail;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** An email waiting for the mail server. */
@Entity
@Table(name = "mail_outbox")
public class OutboxMail {
    @Id
    private UUID id;
    private String recipient;
    private String subject;
    private String bodyText;
    private String bodyHtml;
    private int attempts;
    private Instant notBefore;
    private boolean failed;
    private String lastError;
    private Instant createdAt;

    protected OutboxMail() {}

    public OutboxMail(String recipient, String subject, String bodyText, String bodyHtml) {
        this.id = UUID.randomUUID();
        this.recipient = recipient;
        this.subject = subject.length() > 500 ? subject.substring(0, 500) : subject;
        this.bodyText = bodyText;
        this.bodyHtml = bodyHtml;
        this.createdAt = Instant.now();
        this.notBefore = createdAt.truncatedTo(ChronoUnit.MILLIS); // the due check is no finer than this
    }

    public void retry(String error, int maxAttempts) {
        attempts++;
        lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        if (attempts >= maxAttempts) failed = true;
        else notBefore = Instant.now().plus(Duration.ofMinutes(1L << Math.min(attempts, 8)));
    }

    public UUID getId() { return id; }
    public String getRecipient() { return recipient; }
    public String getSubject() { return subject; }
    public String getBodyText() { return bodyText; }
    public String getBodyHtml() { return bodyHtml; }
    public String getLastError() { return lastError; }
    public int getAttempts() { return attempts; }
}
