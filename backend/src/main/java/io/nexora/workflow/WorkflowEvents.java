package io.nexora.workflow;

import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import io.nexora.workflow.WorkflowEvent.Type;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Turns audited changes into workflow events, in the same transaction as the change. */
@Component
public class WorkflowEvents {
    private static final Map<String, Type> TRIGGERS = Map.of(
            "FILE_UPLOAD", Type.CREATED, "ITEM_CREATE", Type.CREATED,
            "FILE_UPDATE", Type.CHANGED, "CHECK_IN", Type.CHANGED, "METADATA_UPDATE", Type.CHANGED, "ITEM_UPDATE", Type.CHANGED,
            "ITEM_RESTORE", Type.CHANGED);
    /** What makes an approval no longer about what the approvers were shown. */
    private static final Set<String> FILE_CHANGES = Set.of("FILE_UPDATE", "CHECK_IN", "VERSION_RESTORE", "PUBLISH", "DELETE");
    private static final Set<String> ITEM_CHANGES = Set.of("METADATA_UPDATE", "ITEM_UPDATE", "ITEM_RESTORE", "DELETE");

    private final WorkflowEventRepository events;
    private final WorkflowRuleRepository rules;
    private final ApprovalRepository approvals;
    private final WorkflowIdentity identity;

    public WorkflowEvents(WorkflowEventRepository events, WorkflowRuleRepository rules, ApprovalRepository approvals, WorkflowIdentity identity) {
        this.events = events;
        this.rules = rules;
        this.approvals = approvals;
        this.identity = identity;
    }

    public void audited(String action, CurrentUser actor, Node node) {
        if (node == null || (actor != null && identity.is(actor.id()))) return;
        boolean file = node.getKind() == Node.Kind.FILE;
        if (!file && node.getKind() != Node.Kind.LIST_ITEM) return;
        if ((file ? FILE_CHANGES : ITEM_CHANGES).contains(action) && approvals.existsByNodeIdAndStatus(node.getId(), Approval.Status.PENDING)) {
            save(node, Type.STALE, actor);
        }
        Type trigger = TRIGGERS.get(action);
        if (trigger != null && rules.existsByEnabledTrue()) save(node, trigger, actor);
    }

    private void save(Node node, Type type, CurrentUser actor) {
        events.save(new WorkflowEvent(node.getId(), type, actor == null ? null : actor.id(), actor == null ? null : actor.displayName()));
    }
}
