package io.nexora.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

/**
 * Sends email through an outbox: {@link #queue} joins the caller's transaction, and a background worker hands the
 * message to the mail server, retrying with growing delays. A rollback sends nothing, and a mail outage loses nothing.
 */
@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final int MAX_ATTEMPTS = 8;

    public record Status(boolean enabled, String server, long waiting, long failed) {}

    private final MailProperties properties;
    private final OutboxRepository outbox;
    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();

    public MailService(MailProperties properties, OutboxRepository outbox) {
        this.properties = properties;
        this.outbox = outbox;
        sender.setHost(properties.host());
        sender.setPort(properties.port());
        if (properties.username() != null && !properties.username().isBlank()) {
            sender.setUsername(properties.username());
            sender.setPassword(properties.password());
        }
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.starttls.enable", String.valueOf(properties.startTls()));
        p.put("mail.smtp.auth", String.valueOf(properties.username() != null && !properties.username().isBlank()));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "20000");
        p.put("mail.smtp.writetimeout", "20000");
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** The address of a page inside NEXORA. */
    public String link(String path) {
        return properties.publicUrl().replaceAll("/+$", "") + org.springframework.web.util.UriUtils.encodePath(path, StandardCharsets.UTF_8);
    }

    /**
     * Queues an email from plain lines. The first line is the heading, and the link becomes a button.
     * Does nothing when email is off or the person has no address.
     */
    public void queue(String to, String subject, List<String> lines, String linkLabel, String link) {
        if (!properties.enabled() || to == null || to.isBlank()) return;
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder("<div style=\"font-family:Segoe UI,Arial,sans-serif;font-size:15px;color:#242424;max-width:560px\">");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            text.append(line).append("\n\n");
            String safe = HtmlUtils.htmlEscape(line);
            html.append(i == 0 ? "<h2 style=\"font-size:20px;font-weight:600;margin:0 0 12px\">" + safe + "</h2>" : "<p style=\"margin:0 0 12px\">" + safe + "</p>");
        }
        if (link != null) {
            text.append(linkLabel).append(": ").append(link).append('\n');
            html.append("<p style=\"margin:20px 0\"><a href=\"").append(HtmlUtils.htmlEscape(link))
                    .append("\" style=\"background:#0f6cbd;color:#fff;padding:10px 18px;border-radius:4px;text-decoration:none\">")
                    .append(HtmlUtils.htmlEscape(linkLabel)).append("</a></p>");
        }
        html.append("<p style=\"color:#707070;font-size:12px;margin-top:28px\">Sent by NEXORA</p></div>");
        outbox.save(new OutboxMail(to.strip(), subject, text.toString(), html.toString()));
    }

    @Scheduled(fixedDelayString = "${nexora.mail.poll-interval:5s}")
    public void work() {
        if (properties.enabled() && properties.workerEnabled()) send();
    }

    /** Sends what is due and returns how many were tried. */
    public int send() {
        List<OutboxMail> due = outbox.due(Instant.now(), PageRequest.of(0, 50));
        for (OutboxMail m : due) {
            try {
                MimeMessage message = sender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
                helper.setFrom(properties.from());
                helper.setTo(m.getRecipient());
                helper.setSubject(m.getSubject());
                helper.setText(m.getBodyText(), m.getBodyHtml());
                sender.send(message);
                outbox.delete(m);
            } catch (MailException | MessagingException e) {
                log.warn("Could not send email to {}: {}", m.getRecipient(), e.getMessage());
                m.retry(e.getMessage(), MAX_ATTEMPTS);
                outbox.save(m);
            }
        }
        return due.size();
    }

    public Status status() {
        return new Status(properties.enabled(), properties.host() + ":" + properties.port(), outbox.countByFailed(false), outbox.countByFailed(true));
    }

    /** Tries the mail server now, without sending anything. */
    public String check() {
        try {
            sender.testConnection();
            return null;
        } catch (MessagingException e) {
            return e.getMessage();
        }
    }
}
