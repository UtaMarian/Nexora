package io.nexora.workflow;

import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.content.*;
import io.nexora.identity.*;
import io.nexora.mail.MailService;
import io.nexora.workflow.Approval.Kind;
import io.nexora.workflow.Approval.Mode;
import io.nexora.workflow.Approval.Status;
import io.nexora.workflow.ApprovalStep.Decision;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Approvals and reviews of one version of a file or list item. An approval covers exactly what its approvers were
 * shown: if the content changes, it is cancelled rather than quietly approving something else. Nobody can approve
 * their own request, and nobody counts twice.
 */
@Service
@Transactional
public class ApprovalService {
    private static final int MAX_PEOPLE = 20;

    public record Request(Kind kind, Mode mode, String title, String message, List<UUID> people, Integer dueDays) {}

    private final ApprovalRepository approvals;
    private final ApprovalStepRepository steps;
    private final WorkflowEventRepository events;
    private final WorkflowIdentity workflow;
    private final NodeRepository nodes;
    private final ContentService content;
    private final DocumentService documents;
    private final FileVersionRepository versions;
    private final PermissionService permissions;
    private final PrincipalRepository principals;
    private final GroupMemberRepository members;
    private final EditLockService editLocks;
    private final MailService mail;
    private final AuditService audit;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public ApprovalService(ApprovalRepository approvals, ApprovalStepRepository steps, WorkflowEventRepository events, WorkflowIdentity workflow,
                           NodeRepository nodes, ContentService content, DocumentService documents, FileVersionRepository versions,
                           PermissionService permissions, PrincipalRepository principals, GroupMemberRepository members,
                           EditLockService editLocks, MailService mail, AuditService audit) {
        this.approvals = approvals;
        this.steps = steps;
        this.events = events;
        this.workflow = workflow;
        this.nodes = nodes;
        this.content = content;
        this.documents = documents;
        this.versions = versions;
        this.permissions = permissions;
        this.principals = principals;
        this.members = members;
        this.editLocks = editLocks;
        this.mail = mail;
        this.audit = audit;
    }

    // ---- starting ----------------------------------------------------------------------------------

    public Approval start(CurrentUser user, UUID nodeId, Request r) {
        Node node = content.get(user, nodeId);
        permissions.require(user, node, Right.EDIT_ITEMS);
        return create(user, node, r);
    }

    /** Starts an approval for a rule. {@code requester} is whoever caused it; rules hold no rights of their own here. */
    Approval startForRule(CurrentUser requester, Node node, Request r) {
        return create(requester, node, r);
    }

    private Approval create(CurrentUser user, Node node, Request r) {
        if (node.getKind() != Node.Kind.FILE && node.getKind() != Node.Kind.LIST_ITEM) {
            throw ApiException.badRequest("not_approvable", "Only files and list items can go through approval");
        }
        if (approvals.existsByNodeIdAndStatus(node.getId(), Status.PENDING)) {
            throw ApiException.conflict("already_pending", "'" + name(node) + "' is already waiting for an approval or review");
        }
        Kind kind = r.kind() == null ? Kind.APPROVAL : r.kind();
        Mode mode = kind == Kind.REVIEW ? (r.mode() == Mode.SEQUENTIAL ? Mode.SEQUENTIAL : Mode.ALL) : r.mode() == null ? Mode.ALL : r.mode();
        List<UUID> people = new ArrayList<>(new LinkedHashSet<>(r.people() == null ? List.<UUID>of() : r.people()));
        if (people.isEmpty()) throw ApiException.badRequest("nobody", "Choose at least one person");
        if (people.size() > MAX_PEOPLE) throw ApiException.badRequest("too_many", "An approval can have at most " + MAX_PEOPLE + " people");
        if (r.dueDays() != null && (r.dueDays() < 1 || r.dueDays() > 365)) throw ApiException.badRequest("invalid_due", "Due date must be within a year");
        String title = r.title() == null || r.title().isBlank() ? (kind == Kind.REVIEW ? "Review " : "Approve ") + name(node) : r.title().strip();
        if (title.length() > 255) throw ApiException.badRequest("invalid_title", "The title is too long");
        if (r.message() != null && r.message().length() > 2000) throw ApiException.badRequest("invalid_message", "The message is too long");

        List<Principal> assignees = new ArrayList<>();
        for (UUID id : people) {
            Principal p = principals.findById(id).orElseThrow(() -> ApiException.notFound("Person or group"));
            if (p.getId().equals(user.id())) {
                throw ApiException.badRequest("self_approval", "You cannot " + (kind == Kind.REVIEW ? "review" : "approve") + " your own request");
            }
            if (expand(p).stream().noneMatch(u -> !u.getId().equals(user.id()) && canSee(u, node))) {
                throw ApiException.badRequest("no_access", p.getDisplayName() + " cannot open '" + name(node) + "'. Share it with them first");
            }
            assignees.add(p);
        }
        Instant due = r.dueDays() == null ? null : Instant.now().plus(Duration.ofDays(r.dueDays()));
        Approval a = approvals.save(new Approval(node.getId(), kind, mode, title, blankToNull(r.message()), user.id(), user.displayName(), versionLabel(node), due));
        for (int i = 0; i < assignees.size(); i++) {
            Principal p = assignees.get(i);
            steps.save(new ApprovalStep(a.getId(), mode == Mode.SEQUENTIAL ? i : 0, p.getId(), p.getDisplayName()));
        }
        audit.record(user, "APPROVAL_REQUEST", node, Map.of("kind", kind.name(), "mode", mode.name(), "approval", a.getId().toString()));
        notifyActive(a, node);
        return a;
    }

    private String versionLabel(Node node) {
        if (node.getCurrentVersionId() == null) return null;
        return versions.findById(node.getCurrentVersionId()).map(FileVersion::label).orElse(null);
    }

    // ---- reading -----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Approval get(CurrentUser user, UUID id) {
        Approval a = approvals.findById(id).orElseThrow(() -> ApiException.notFound("Approval"));
        content.get(user, a.getNodeId()); // no access to the item, no access to its approvals
        return a;
    }

    @Transactional(readOnly = true)
    public List<Approval> forNode(CurrentUser user, UUID nodeId) {
        Node node = content.get(user, nodeId);
        return approvals.findByNodeIdOrderByCreatedAtDesc(node.getId());
    }

    @Transactional(readOnly = true)
    public List<Approval> waitingFor(CurrentUser user) {
        Set<UUID> mine = permissions.principalIds(user);
        List<Approval> out = new ArrayList<>();
        for (Approval a : approvals.waitingFor(mine)) {
            Node node = nodes.findLive(a.getNodeId()).orElse(null);
            if (node != null && permissions.seesLatest(user, node) && canDecide(user, a, steps.findByApprovalIdOrderByPosition(a.getId()), mine)) out.add(a);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Approval> sentBy(CurrentUser user) {
        return approvals.findByRequestedByOrderByCreatedAtDesc(user.id(), PageRequest.of(0, 100)).stream()
                .filter(a -> nodes.findLive(a.getNodeId()).filter(n -> permissions.has(user, n, Right.VIEW)).isPresent()).toList();
    }

    @Transactional(readOnly = true)
    public boolean canDecide(CurrentUser user, Approval a) {
        return a.isPending() && canDecide(user, a, steps.findByApprovalIdOrderByPosition(a.getId()), permissions.principalIds(user));
    }

    private boolean canDecide(CurrentUser user, Approval a, List<ApprovalStep> all, Set<UUID> mine) {
        return stepFor(user, a, all, mine) != null;
    }

    /** The step this person answers now, or null. */
    private ApprovalStep stepFor(CurrentUser user, Approval a, List<ApprovalStep> all, Set<UUID> mine) {
        if (user.id().equals(a.getRequestedBy())) return null;
        if (all.stream().anyMatch(s -> user.id().equals(s.getDecidedBy()))) return null;
        return active(all).stream().filter(s -> s.getPrincipalId() != null && mine.contains(s.getPrincipalId())).findFirst().orElse(null);
    }

    /** Steps waiting for an answer now: all of them, or the next one in a sequence. */
    private static List<ApprovalStep> active(List<ApprovalStep> all) {
        int next = all.stream().filter(ApprovalStep::isPending).mapToInt(ApprovalStep::getPosition).min().orElse(-1);
        return all.stream().filter(s -> s.isPending() && s.getPosition() == next).toList();
    }

    @Transactional(readOnly = true)
    public List<UUID> activeSteps(Approval a) {
        return active(steps.findByApprovalIdOrderByPosition(a.getId())).stream().map(ApprovalStep::getId).toList();
    }

    @Transactional(readOnly = true)
    public List<ApprovalStep> steps(Approval a) {
        return steps.findByApprovalIdOrderByPosition(a.getId());
    }

    // ---- deciding ----------------------------------------------------------------------------------

    public Approval decide(CurrentUser user, UUID id, boolean approve, String comment) {
        Approval a = pending(user, id);
        Node node = content.get(user, a.getNodeId());
        if (events.existsByNodeIdAndEventType(a.getNodeId(), WorkflowEvent.Type.STALE)) {
            throw ApiException.conflict("content_changed", "'" + name(node) + "' changed after this request was sent");
        }
        List<ApprovalStep> all = steps.findByApprovalIdOrderByPosition(a.getId());
        ApprovalStep step = stepFor(user, a, all, permissions.principalIds(user));
        if (step == null) throw ApiException.forbidden("This is not waiting for your answer");
        if (!permissions.seesLatest(user, node)) throw ApiException.forbidden("You cannot see the draft this request is about");
        if (!approve && (comment == null || comment.isBlank())) {
            throw ApiException.badRequest("comment_required", "Say what needs to change");
        }
        step.decide(approve ? Decision.APPROVED : Decision.REJECTED, user.id(), user.displayName(), blankToNull(comment));
        steps.save(step);
        audit.record(user, "APPROVAL_DECISION", node, Map.of("decision", step.getDecision().name(), "approval", a.getId().toString()));
        advance(a, node, all, user);
        return a;
    }

    private void advance(Approval a, Node node, List<ApprovalStep> all, CurrentUser user) {
        boolean rejected = all.stream().anyMatch(s -> s.getDecision() == Decision.REJECTED);
        boolean open = all.stream().anyMatch(ApprovalStep::isPending);
        if (a.getKind() == Kind.REVIEW) {
            if (!open) finish(a, node, all, rejected ? Status.CHANGES_REQUESTED : Status.APPROVED, rejected ? "Changes were requested" : "Everyone reviewed it", user);
            else if (a.getMode() == Mode.SEQUENTIAL) notifyActive(a, node);
        } else if (rejected) {
            finish(a, node, all, Status.REJECTED, "Rejected by " + user.displayName(), user);
        } else if (!open || a.getMode() == Mode.ANY) {
            finish(a, node, all, Status.APPROVED, "Approved by " + user.displayName(), user);
        } else if (a.getMode() == Mode.SEQUENTIAL) {
            notifyActive(a, node);
        }
    }

    private void finish(Approval a, Node node, List<ApprovalStep> all, Status status, String outcome, CurrentUser actor) {
        all.stream().filter(ApprovalStep::isPending).forEach(ApprovalStep::skip);
        steps.saveAll(all);
        String result = outcome;
        if (status == Status.APPROVED && a.getKind() == Kind.APPROVAL) result = outcome + publish(node, outcome);
        a.finish(status, result);
        audit.record(actor, "APPROVAL_COMPLETE", node, Map.of("status", status.name(), "approval", a.getId().toString()));
        events.save(new WorkflowEvent(node.getId(), status == Status.APPROVED ? WorkflowEvent.Type.APPROVED : WorkflowEvent.Type.REJECTED,
                actor.id(), actor.displayName()));
        tellRequester(a, node, status);
    }

    /** An approved draft becomes the published major version, when the library has drafts and nothing is in the way. */
    private String publish(Node node, String comment) {
        if (node.getKind() != Node.Kind.FILE) return "";
        LibrarySettings settings = LibrarySettings.of(content.libraryOf(node));
        if (!settings.minorVersionsEnabled()) return "";
        boolean major = versions.findById(node.getCurrentVersionId()).map(FileVersion::isMajor).orElse(true);
        if (major) return "";
        if (node.isCheckedOut() || editLocks.activeFor(List.of(node.getId())).containsKey(node.getId())) {
            return ". It is checked out or open, so it was not published";
        }
        documents.publish(workflow.user(), node.getId(), comment);
        return " and published as version " + versionLabel(node);
    }

    // ---- changing --------------------------------------------------------------------------------

    public Approval reassign(CurrentUser user, UUID id, UUID principalId) {
        Approval a = pending(user, id);
        Node node = content.get(user, a.getNodeId());
        List<ApprovalStep> all = steps.findByApprovalIdOrderByPosition(a.getId());
        Set<UUID> mine = permissions.principalIds(user);
        ApprovalStep step = user.id().equals(a.getRequestedBy()) || permissions.has(user, node, Right.MANAGE_PERMISSIONS)
                ? active(all).stream().findFirst().orElse(null) : stepFor(user, a, all, mine);
        if (step == null) throw ApiException.forbidden("This is not waiting for your answer");
        Principal to = principals.findById(principalId).orElseThrow(() -> ApiException.notFound("Person or group"));
        if (to.getId().equals(a.getRequestedBy())) throw ApiException.badRequest("self_approval", "The person who asked cannot answer");
        if (expand(to).stream().noneMatch(u -> canSee(u, node))) {
            throw ApiException.badRequest("no_access", to.getDisplayName() + " cannot open '" + name(node) + "'. Share it with them first");
        }
        String from = step.getPrincipalName();
        step.reassign(to.getId(), to.getDisplayName());
        audit.record(user, "APPROVAL_REASSIGN", node, Map.of("from", String.valueOf(from), "to", to.getLogin(), "approval", a.getId().toString()));
        notifyPrincipal(a, node, to);
        return a;
    }

    public Approval cancel(CurrentUser user, UUID id) {
        Approval a = pending(user, id);
        Node node = content.get(user, a.getNodeId());
        if (!user.id().equals(a.getRequestedBy())) permissions.require(user, node, Right.MANAGE_PERMISSIONS);
        close(a, node, "Cancelled by " + user.displayName(), user);
        return a;
    }

    /** The content changed under a pending approval. Handled by the worker, so it also runs for bulk changes. */
    void contentChanged(UUID nodeId) {
        for (Approval found : approvals.findByNodeIdAndStatus(nodeId, Status.PENDING)) {
            Approval a = approvals.lockedById(found.getId()).orElse(found);
            entityManager.refresh(a);
            Node node = nodes.findById(nodeId).orElse(null);
            if (node == null || !a.isPending()) continue;
            close(a, node, "Cancelled because it changed after the request was sent", workflow.user());
            Principal requester = a.getRequestedBy() == null ? null : principals.findById(a.getRequestedBy()).orElse(null);
            if (requester != null) {
                mail.queue(requester.getEmail(), "[NEXORA] Request again: " + name(node) + " changed", List.of(name(node) + " changed during your request",
                        "The request was cancelled so nobody approves something they did not see. Send a new one when it is ready."), "Open", mail.link(node.getPath()));
            }
        }
    }

    private void close(Approval a, Node node, String outcome, CurrentUser actor) {
        List<ApprovalStep> all = steps.findByApprovalIdOrderByPosition(a.getId());
        all.stream().filter(ApprovalStep::isPending).forEach(ApprovalStep::skip);
        steps.saveAll(all);
        a.finish(Status.CANCELLED, outcome);
        audit.record(actor, "APPROVAL_CANCEL", node, Map.of("approval", a.getId().toString(), "reason", outcome));
    }

    /** Reminds whoever an overdue approval is waiting for, once a day. */
    void remindOverdue(Instant now) {
        for (Approval a : approvals.overdue(now, now.minus(Duration.ofDays(1)))) {
            a.setRemindedAt(now);
            nodes.findLive(a.getNodeId()).ifPresent(node -> {
                for (ApprovalStep s : active(steps.findByApprovalIdOrderByPosition(a.getId()))) {
                    principals.findById(s.getPrincipalId() == null ? new UUID(0, 0) : s.getPrincipalId()).ifPresent(p -> {
                        for (Principal u : expand(p)) {
                            if (u.isEnabled() && !u.getId().equals(a.getRequestedBy()) && canSee(u, node)) {
                                mail.queue(u.getEmail(), "[NEXORA] Overdue: " + a.getTitle(), List.of("This is waiting for you", a.getTitle()), "Open", mail.link("/approvals/" + a.getId()));
                            }
                        }
                    });
                }
            });
        }
    }

    private Approval pending(CurrentUser user, UUID id) {
        Approval a = approvals.lockedById(id).orElseThrow(() -> ApiException.notFound("Approval"));
        content.get(user, a.getNodeId()); // no access to the item, no access to its approvals
        if (!a.isPending()) throw ApiException.conflict("not_pending", "This request is already finished");
        return a;
    }

    // ---- people ----------------------------------------------------------------------------------

    private void notifyActive(Approval a, Node node) {
        for (ApprovalStep s : active(steps.findByApprovalIdOrderByPosition(a.getId()))) {
            if (s.getPrincipalId() != null) principals.findById(s.getPrincipalId()).ifPresent(p -> notifyPrincipal(a, node, p));
        }
    }

    private void notifyPrincipal(Approval a, Node node, Principal p) {
        String verb = a.getKind() == Kind.REVIEW ? "Review requested" : "Approval needed";
        List<String> lines = new ArrayList<>(List.of(verb + ": " + name(node), a.getRequestedByName() + " is asking you to look at this."));
        if (a.getMessage() != null) lines.add(a.getMessage());
        if (a.getDueAt() != null) lines.add("Due " + a.getDueAt().toString().substring(0, 10));
        for (Principal u : expand(p)) {
            if (u.isEnabled() && !u.getId().equals(a.getRequestedBy()) && canSee(u, node)) {
                mail.queue(u.getEmail(), "[NEXORA] " + verb + ": " + name(node), lines, "Open the request", mail.link("/approvals/" + a.getId()));
            }
        }
    }

    private void tellRequester(Approval a, Node node, Status status) {
        Principal p = a.getRequestedBy() == null ? null : principals.findById(a.getRequestedBy()).orElse(null);
        if (p == null || !p.isEnabled()) return;
        String word = switch (status) {
            case APPROVED -> a.getKind() == Kind.REVIEW ? "Review finished" : "Approved";
            case REJECTED -> "Rejected";
            case CHANGES_REQUESTED -> "Changes requested";
            default -> "Finished";
        };
        List<String> lines = new ArrayList<>(List.of(word + ": " + name(node), a.getOutcome()));
        steps.findByApprovalIdOrderByPosition(a.getId()).stream().filter(s -> s.getComment() != null)
                .forEach(s -> lines.add(s.getDecidedByName() + ": " + s.getComment()));
        mail.queue(p.getEmail(), "[NEXORA] " + word + ": " + name(node), lines, "See the result", mail.link("/approvals/" + a.getId()));
    }

    /** A person, or everyone in a group, however deeply nested. */
    List<Principal> expand(Principal p) {
        if (p.getKind() == Principal.Kind.USER) return List.of(p);
        List<Principal> users = new ArrayList<>();
        Set<UUID> seen = new HashSet<>(Set.of(p.getId()));
        Deque<UUID> todo = new ArrayDeque<>(List.of(p.getId()));
        while (!todo.isEmpty()) {
            for (GroupMember m : members.findByGroupId(todo.poll())) {
                if (!seen.add(m.getMemberId())) continue;
                principals.findById(m.getMemberId()).ifPresent(x -> {
                    if (x.getKind() == Principal.Kind.USER) users.add(x);
                    else todo.add(x.getId());
                });
            }
        }
        return users;
    }

    private boolean canSee(Principal p, Node node) {
        return permissions.seesLatest(new CurrentUser(p.getId(), p.getLogin(), p.getDisplayName(), p.isAdmin()), node);
    }

    private static String name(Node n) {
        return n.getTitle() != null ? n.getTitle() : n.getName();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
