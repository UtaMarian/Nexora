package io.nexora.alerts;

import io.nexora.acl.PermissionLevel;
import io.nexora.acl.PermissionService;
import io.nexora.alerts.Alert.Frequency;
import io.nexora.common.ApiException;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.mail.MailProperties;
import io.nexora.mail.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * "Alert me". The audit log hands every interesting change to {@link #audited} in the writer's transaction. A worker
 * then matches changes against alerts on the node and its ancestors, and either emails at once or saves a line for a
 * daily or weekly summary.
 *
 * <p>Access is checked for each person when the email is prepared, not when the alert was set up, so someone who lost
 * access stops hearing about the content. Changes wait a moment first, so the autosaves of one editing session become
 * a single email.
 */
@Service
public class AlertService {
    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final Duration SETTLE = Duration.ofMinutes(1);
    private static final Map<String, String> VERBS = Map.of(
            "FILE_UPLOAD", "added", "FILE_UPDATE", "changed", "ITEM_CREATE", "added", "METADATA_UPDATE", "changed",
            "ITEM_RESTORE", "restored an older version of", "FOLDER_CREATE", "added", "RENAME", "renamed", "MOVE", "moved",
            "DELETE", "deleted", "PUBLISH", "published");

    public record AlertDto(UUID id, UUID nodeId, String title, String path, String kind, Frequency frequency, Instant createdAt) {}

    private final AlertRepository alerts;
    private final AlertEventRepository events;
    private final DigestItemRepository digests;
    private final NodeRepository nodes;
    private final PrincipalRepository principals;
    private final PermissionService permissions;
    private final MailService mail;
    private final MailProperties properties;
    private final TransactionTemplate tx;

    public AlertService(AlertRepository alerts, AlertEventRepository events, DigestItemRepository digests, NodeRepository nodes, PrincipalRepository principals,
                        PermissionService permissions, MailService mail, MailProperties properties,
                        PlatformTransactionManager transactions) {
        this.alerts = alerts;
        this.events = events;
        this.digests = digests;
        this.nodes = nodes;
        this.principals = principals;
        this.permissions = permissions;
        this.mail = mail;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Called by the audit log. Without any alerts there is nothing to record. */
    public void audited(String action, CurrentUser actor, Node node) {
        if (node == null || !VERBS.containsKey(action) || alerts.count() == 0) return;
        events.save(new AlertEvent(node.getId(), action, actor == null ? null : actor.id(), actor == null ? null : actor.displayName()));
    }

    // ---- what people set up ----------------------------------------------------------------------

    public AlertDto subscribe(CurrentUser user, UUID nodeId, Frequency frequency) {
        Node node = visible(user, nodeId);
        Alert alert = alerts.findByUserIdAndNodeId(user.id(), nodeId).orElseGet(() -> new Alert(user.id(), nodeId, frequency));
        alert.setFrequency(frequency == null ? Frequency.IMMEDIATE : frequency);
        alerts.save(alert);
        return dto(alert, node);
    }

    public Optional<AlertDto> find(CurrentUser user, UUID nodeId) {
        Node node = visible(user, nodeId);
        return alerts.findByUserIdAndNodeId(user.id(), nodeId).map(a -> dto(a, node));
    }

    public List<AlertDto> mine(CurrentUser user) {
        List<AlertDto> out = new ArrayList<>();
        for (Alert a : alerts.findByUserIdOrderByCreatedAtDesc(user.id())) {
            nodes.findLive(a.getNodeId()).filter(n -> permissions.has(user, n, io.nexora.acl.Right.VIEW)).ifPresent(n -> out.add(dto(a, n)));
        }
        return out;
    }

    public void unsubscribe(CurrentUser user, UUID alertId) {
        Alert alert = alerts.findById(alertId).filter(a -> a.getUserId().equals(user.id())).orElseThrow(() -> ApiException.notFound("Alert"));
        alerts.delete(alert);
    }

    private Node visible(CurrentUser user, UUID nodeId) {
        Node node = nodes.findLive(nodeId).orElseThrow(() -> ApiException.notFound("Item"));
        permissions.require(user, node, io.nexora.acl.Right.VIEW);
        return node;
    }

    private static AlertDto dto(Alert a, Node n) {
        return new AlertDto(a.getId(), n.getId(), n.getTitle() != null ? n.getTitle() : n.getName(), n.getPath(), n.getKind().name(), a.getFrequency(), a.getCreatedAt());
    }

    // ---- the worker ------------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${nexora.mail.poll-interval:5s}")
    public void work() {
        if (properties.enabled() && properties.workerEnabled()) {
            match(Instant.now().minus(SETTLE));
            summarise(Instant.now());
        }
    }

    /** Matches every change made before {@code before} against alerts. */
    public void match(Instant before) {
        List<AlertEvent> due;
        do {
            due = events.due(before, PageRequest.of(0, 200));
            if (due.isEmpty()) return;
            // the last change per person and item stands for the rest: autosaves become one email
            Map<String, AlertEvent> latest = new LinkedHashMap<>();
            for (AlertEvent e : due) latest.put(e.getNodeId() + "|" + e.getActorId(), e);
            for (AlertEvent e : latest.values()) {
                try {
                    tx.executeWithoutResult(s -> notify(e));
                } catch (RuntimeException ex) {
                    log.warn("Could not process the alert for {}: {}", e.getNodeId(), ex.toString());
                }
            }
            events.deleteAllInBatch(due);
        } while (due.size() == 200);
    }

    private void notify(AlertEvent e) {
        Node node = nodes.findById(e.getNodeId()).orElse(null);
        if (node == null) return;
        List<UUID> chain = new ArrayList<>();
        for (Node n = node; n != null; n = n.getParentId() == null ? null : nodes.findById(n.getParentId()).orElse(null)) chain.add(n.getId());
        Map<UUID, Alert> perUser = new HashMap<>();
        // nearest alert wins when someone follows both a file and its library
        for (UUID id : chain) {
            alerts.findByNodeIdIn(List.of(id)).forEach(a -> perUser.putIfAbsent(a.getUserId(), a));
        }
        String name = node.getTitle() != null ? node.getTitle() : node.getName();
        String who = e.getActorName() == null ? "Someone" : e.getActorName();
        String summary = who + " " + VERBS.get(e.getAction()) + " " + name;
        String link = e.getAction().equals("DELETE") ? parentPath(node) : node.getPath();

        for (Alert a : perUser.values()) {
            if (a.getUserId().equals(e.getActorId())) continue;
            Principal p = principals.findById(a.getUserId()).orElse(null);
            if (p == null || !p.isEnabled()) continue;
            if (!canSee(p, node)) continue;
            if (a.getFrequency() == Frequency.IMMEDIATE) {
                mail.queue(p.getEmail(), "[NEXORA] " + summary, List.of(summary, "In " + parentPath(node)), "Open in NEXORA", mail.link(link));
            } else {
                digests.save(new DigestItem(a.getId(), summary, link, e.getOccurredAt()));
            }
        }
    }

    private boolean canSee(Principal p, Node node) {
        CurrentUser user = new CurrentUser(p.getId(), p.getLogin(), p.getDisplayName(), p.isAdmin());
        PermissionLevel level = permissions.effectiveLevel(user, node);
        return level != null;
    }

    private static String parentPath(Node node) {
        int slash = node.getPath().lastIndexOf('/');
        return slash > 0 ? node.getPath().substring(0, slash) : node.getPath();
    }

    /** Sends the summaries that are due. */
    public void summarise(Instant now) {
        for (Alert alert : alerts.findByFrequencyIn(List.of(Frequency.DAILY, Frequency.WEEKLY))) {
            Duration every = Duration.ofDays(alert.getFrequency() == Frequency.DAILY ? 1 : 7);
            Instant last = alert.getLastSentAt() != null ? alert.getLastSentAt() : alert.getCreatedAt();
            if (last.plus(every).isAfter(now)) continue;
            try {
                tx.executeWithoutResult(s -> summarise(alert, now));
            } catch (RuntimeException e) {
                log.warn("Could not send the summary for alert {}: {}", alert.getId(), e.toString());
            }
        }
    }

    private void summarise(Alert alert, Instant now) {
        List<DigestItem> lines = digests.findByAlertIdOrderByOccurredAt(alert.getId());
        Alert fresh = alerts.findById(alert.getId()).orElse(null);
        if (fresh == null) return;
        fresh.setLastSentAt(now);
        if (lines.isEmpty()) return;
        Node node = nodes.findById(alert.getNodeId()).orElse(null);
        Principal p = principals.findById(alert.getUserId()).orElse(null);
        if (node != null && p != null && p.isEnabled() && canSee(p, node)) {
            String name = node.getTitle() != null ? node.getTitle() : node.getName();
            String heading = lines.size() + (lines.size() == 1 ? " change" : " changes") + " in " + name;
            List<String> body = new ArrayList<>();
            body.add(heading);
            lines.stream().limit(50).forEach(l -> body.add(l.getSummary()));
            if (lines.size() > 50) body.add("And " + (lines.size() - 50) + " more.");
            mail.queue(p.getEmail(), "[NEXORA] " + heading, body, "Open in NEXORA", mail.link(node.getPath()));
        }
        digests.deleteAllInBatch(lines);
    }
}
