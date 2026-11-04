package io.nexora.workflow;

import io.nexora.acl.PermissionService;
import io.nexora.common.ApiException;
import io.nexora.content.*;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.mail.MailService;
import io.nexora.workflow.RuleDefinition.Action;
import io.nexora.workflow.RuleDefinition.Condition;
import io.nexora.workflow.RuleDefinition.Who;
import io.nexora.workflow.WorkflowEvent.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Runs rules in the background. Each rule runs in its own transaction, so a rule that fails leaves nothing half done
 * and cannot stop the others, and every run is logged for the people who own the rule.
 *
 * <p>Rules act as the Workflow account, inside their own site. Email only reaches people who can already see the item,
 * and no rule can take a person's address from outside the directory, so a rule cannot leak content.
 */
@Component
public class WorkflowEngine {
    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final int BATCH = 200;

    private final WorkflowEventRepository events;
    private final WorkflowRuleRepository rules;
    private final WorkflowRunRepository runs;
    private final WorkflowIdentity workflow;
    private final RuleService ruleService;
    private final ApprovalService approvals;
    private final ApprovalRepository approvalRows;
    private final NodeRepository nodes;
    private final ContentService content;
    private final PrincipalRepository principals;
    private final PermissionService permissions;
    private final MailService mail;
    private final TransactionTemplate tx;
    private final boolean workerEnabled;
    private volatile Instant lastSweep = Instant.EPOCH;

    public WorkflowEngine(WorkflowEventRepository events, WorkflowRuleRepository rules, WorkflowRunRepository runs, WorkflowIdentity workflow,
                          RuleService ruleService, ApprovalService approvals, ApprovalRepository approvalRows, NodeRepository nodes,
                          ContentService content, PrincipalRepository principals, PermissionService permissions, MailService mail,
                          PlatformTransactionManager transactions, @Value("${nexora.workflow.worker-enabled:true}") boolean workerEnabled) {
        this.events = events;
        this.rules = rules;
        this.runs = runs;
        this.workflow = workflow;
        this.ruleService = ruleService;
        this.approvals = approvals;
        this.approvalRows = approvalRows;
        this.nodes = nodes;
        this.content = content;
        this.principals = principals;
        this.permissions = permissions;
        this.mail = mail;
        this.tx = new TransactionTemplate(transactions);
        this.workerEnabled = workerEnabled;
    }

    @Scheduled(fixedDelayString = "${nexora.workflow.poll-interval:5s}")
    public void work() {
        if (!workerEnabled) return;
        drain();
        Instant now = Instant.now();
        if (lastSweep.plusSeconds(600).isBefore(now)) {
            lastSweep = now;
            sweepDates(LocalDate.now(ZoneOffset.UTC));
            tx.executeWithoutResult(s -> approvals.remindOverdue(now));
        }
    }

    /** Handles everything waiting. */
    public void drain() {
        List<WorkflowEvent> batch;
        do {
            batch = events.next(PageRequest.of(0, BATCH));
            Set<String> seen = new HashSet<>();
            for (WorkflowEvent e : batch) {
                // several saves in a moment count once, and the order of the batch is kept
                if (!seen.add(e.getNodeId() + "|" + e.getEventType())) continue;
                try {
                    handle(e);
                } catch (RuntimeException ex) {
                    log.warn("Could not handle workflow event {} on {}: {}", e.getEventType(), e.getNodeId(), ex.toString());
                }
            }
            if (!batch.isEmpty()) events.deleteAllInBatch(batch);
        } while (batch.size() == BATCH);
    }

    private void handle(WorkflowEvent e) {
        if (e.getEventType() == Type.STALE) {
            tx.executeWithoutResult(s -> approvals.contentChanged(e.getNodeId()));
            return;
        }
        Node node = nodes.findLive(e.getNodeId()).orElse(null);
        if (node == null) return;
        Node container = container(node);
        if (container == null) return;
        for (WorkflowRule rule : rules.findByContainerIdOrderByCreatedAt(container.getId())) {
            if (rule.isEnabled() && ruleService.definition(rule).trigger().equals(e.getEventType().name())) {
                run(rule, node.getId(), e.getActorId(), null);
            }
        }
    }

    /** The library or list a rule on this node belongs to. Attachments and folders are not rule subjects. */
    private Node container(Node node) {
        if (node.getKind() == Node.Kind.LIST_ITEM) return nodes.findById(node.getParentId()).orElse(null);
        if (node.getKind() != Node.Kind.FILE) return null;
        Node owner = content.libraryOf(node);
        return owner.getKind() == Node.Kind.LIBRARY ? owner : null;
    }

    /** Fires date rules whose day has come. Each date fires once. */
    public void sweepDates(LocalDate today) {
        for (WorkflowRule rule : rules.findByEnabledTrue()) {
            RuleDefinition d = ruleService.definition(rule);
            if (!d.trigger().equals("DATE")) continue;
            try {
                Node container = nodes.findLive(rule.getContainerId()).orElse(null);
                if (container == null) continue;
                String wanted = today.plusDays(d.daysBefore()).toString();
                for (Node n : nodes.findLiveDescendants(NodeRepository.likePrefix(container.getPath()))) {
                    Object value = n.getMetadata().get(d.dateColumn());
                    if (value == null || !value.toString().startsWith(wanted) || container(n) == null) continue;
                    String key = n.getId() + ":" + wanted;
                    if (!runs.existsByRuleIdAndFireKey(rule.getId(), key)) run(rule, n.getId(), null, key);
                }
            } catch (RuntimeException e) {
                log.warn("Could not check rule {}: {}", rule.getName(), e.toString());
            }
        }
    }

    private void run(WorkflowRule rule, UUID nodeId, UUID actorId, String fireKey) {
        WorkflowRun outcome;
        String path = nodes.findById(nodeId).map(Node::getPath).orElse("");
        try {
            outcome = tx.execute(s -> {
                Node node = nodes.findLive(nodeId).orElse(null);
                if (node == null) return new WorkflowRun(rule.getId(), nodeId, path, WorkflowRun.Status.SKIPPED, "The item is gone", fireKey);
                RuleDefinition d = ruleService.definition(rule);
                for (Condition c : d.conditions()) {
                    if (!matches(node, c)) return new WorkflowRun(rule.getId(), nodeId, path, WorkflowRun.Status.SKIPPED, "Conditions not met", fireKey);
                }
                List<String> done = new ArrayList<>();
                for (Action a : d.actions()) done.add(act(a, node, actorId));
                return new WorkflowRun(rule.getId(), nodeId, nodes.findById(nodeId).map(Node::getPath).orElse(path), WorkflowRun.Status.DONE,
                        String.join("; ", done), fireKey);
            });
        } catch (RuntimeException e) {
            String reason = e instanceof ApiException ? e.getMessage() : e.toString();
            outcome = new WorkflowRun(rule.getId(), nodeId, path, WorkflowRun.Status.FAILED, reason, fireKey);
        }
        WorkflowRun record = outcome;
        tx.executeWithoutResult(s -> runs.save(record));
    }

    static boolean matches(String actual, Condition c) {
        String text = actual == null ? "" : actual.strip().toLowerCase(Locale.ROOT);
        String wanted = c.value() == null ? "" : c.value().strip().toLowerCase(Locale.ROOT);
        return switch (c.operator()) {
            case "eq" -> text.equals(wanted);
            case "ne" -> !text.equals(wanted);
            case "contains" -> text.contains(wanted);
            case "gt" -> compare(text, wanted) > 0;
            case "lt" -> compare(text, wanted) < 0;
            case "empty" -> text.isEmpty();
            case "notEmpty" -> !text.isEmpty();
            default -> false;
        };
    }

    private static int compare(String a, String b) {
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b); // ISO dates sort as text
        }
    }

    private boolean matches(Node n, Condition c) {
        return matches(value(n, c.column()), c);
    }

    private String value(Node n, String column) {
        return switch (column) {
            case "Title" -> n.getTitle() != null ? n.getTitle() : n.getName();
            case "Name" -> n.getName();
            case "FileType" -> MimeTypes.extension(n.getName());
            case "CreatedBy" -> displayName(n.getCreatedBy());
            case "ModifiedBy" -> displayName(n.getModifiedBy());
            default -> text(n.getMetadata().get(column));
        };
    }

    private static String text(Object v) {
        if (v == null) return "";
        if (v instanceof Boolean b) return b ? "Yes" : "No";
        if (v instanceof Map<?, ?> m) return String.valueOf(m.get(m.containsKey("displayName") ? "displayName" : "title"));
        return v.toString();
    }

    private String displayName(UUID id) {
        return id == null ? "" : principals.findById(id).map(Principal::getDisplayName).orElse("");
    }

    // ---- actions -----------------------------------------------------------------------------------

    private String act(Action a, Node node, UUID actorId) {
        return switch (a.type()) {
            case "SEND_EMAIL" -> email(a, node, actorId);
            case "SET_VALUE" -> setValue(a, node);
            case "REQUEST_APPROVAL", "REQUEST_REVIEW" -> request(a, node, actorId);
            case "MOVE_TO_FOLDER" -> move(a, node);
            case "CREATE_ITEM" -> createItem(a, node, actorId);
            default -> throw new IllegalStateException(a.type());
        };
    }

    private String email(Action a, Node node, UUID actorId) {
        String who = displayName(actorId);
        int sent = 0;
        Set<UUID> reached = new HashSet<>();
        for (Principal p : people(a.people(), node)) {
            for (Principal u : approvals.expand(p)) {
                if (!u.isEnabled() || !reached.add(u.getId()) || !canSee(u, node)) continue;
                List<String> lines = new ArrayList<>(List.of(fill(a.subject(), node, who)));
                if (a.body() != null && !a.body().isBlank()) lines.add(fill(a.body(), node, who));
                mail.queue(u.getEmail(), "[NEXORA] " + fill(a.subject(), node, who), lines, "Open in NEXORA", mail.link(node.getPath()));
                sent++;
            }
        }
        return "Emailed " + sent + (sent == 1 ? " person" : " people");
    }

    /** Replaces {title}, {by} and {link} in text a rule author wrote. */
    private String fill(String text, Node node, String who) {
        return text.replace("{title}", node.getTitle() != null ? node.getTitle() : node.getName())
                .replace("{by}", who.isEmpty() ? "Someone" : who).replace("{link}", mail.link(node.getPath()));
    }

    private String setValue(Action a, Node node) {
        Map<String, Object> patch = new HashMap<>();
        patch.put(a.column(), a.value() == null || a.value().isBlank() ? null : a.value());
        content.update(workflow.user(), node.getId(), null, null, null, patch);
        return "Set a column";
    }

    private String request(Action a, Node node, UUID actorId) {
        if (approvalRows.existsByNodeIdAndStatus(node.getId(), Approval.Status.PENDING)) return "An approval was already running";
        UUID requester = actorId != null && !workflow.is(actorId) ? actorId : node.getModifiedBy();
        Principal p = principals.findById(requester).orElseThrow(() -> new IllegalStateException("Nobody to request it"));
        CurrentUser as = new CurrentUser(p.getId(), p.getLogin(), p.getDisplayName(), false);
        List<UUID> people = people(a.people(), node).stream().map(Principal::getId).filter(id -> !id.equals(requester)).toList();
        Approval.Kind kind = a.type().equals("REQUEST_REVIEW") ? Approval.Kind.REVIEW : Approval.Kind.APPROVAL;
        Approval.Mode mode = a.mode() == null || a.mode().isBlank() ? null : Approval.Mode.valueOf(a.mode());
        approvals.startForRule(as, node, new ApprovalService.Request(kind, mode, null, null, people, a.dueDays()));
        return kind == Approval.Kind.REVIEW ? "Started a review" : "Started an approval";
    }

    private String move(Action a, Node node) {
        Node library = content.libraryOf(node);
        Node target = library;
        for (String part : a.folder().split("/")) {
            if (part.isBlank()) continue;
            String path = target.getPath() + "/" + Names.item(part);
            Node next = nodes.findLiveByPath(path).orElse(null);
            target = next != null ? next : content.createFolder(workflow.user(), target.getId(), part);
        }
        if (target.getId().equals(node.getParentId())) return "Already in " + a.folder();
        content.move(workflow.user(), node.getId(), target.getId());
        return "Moved to " + a.folder();
    }

    private String createItem(Action a, Node node, UUID actorId) {
        String site = node.getPath().split("/", 4)[2];
        Node list = nodes.findLiveByPath("/sites/" + site + "/" + a.list()).filter(n -> n.getKind() == Node.Kind.LIST)
                .orElseThrow(() -> ApiException.notFound("List"));
        content.createItem(workflow.user(), list.getId(), fill(a.title(), node, displayName(actorId)), Map.of());
        return "Added an item to " + list.getName();
    }

    private List<Principal> people(List<Who> who, Node node) {
        List<Principal> out = new ArrayList<>();
        for (Who w : who) {
            Optional<Principal> p = switch (w.kind()) {
                case Who.PERSON -> ruleService.find(w.login());
                case Who.CREATOR -> principals.findById(node.getCreatedBy());
                case Who.EDITOR -> principals.findById(node.getModifiedBy());
                case Who.COLUMN -> node.getMetadata().get(w.column()) instanceof Map<?, ?> m && m.get("id") != null
                        ? principals.findById(UUID.fromString(m.get("id").toString())) : Optional.empty();
                default -> Optional.empty();
            };
            p.filter(x -> out.stream().noneMatch(y -> y.getId().equals(x.getId()))).ifPresent(out::add);
        }
        return out;
    }

    private boolean canSee(Principal p, Node node) {
        return permissions.effectiveLevel(new CurrentUser(p.getId(), p.getLogin(), p.getDisplayName(), p.isAdmin()), node) != null;
    }
}
