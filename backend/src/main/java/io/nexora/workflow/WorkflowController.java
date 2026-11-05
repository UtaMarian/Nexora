package io.nexora.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.common.ApiException;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.identity.CurrentUser;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/** Approvals, reviews and rules. */
@RestController
@RequestMapping("/api/v1")
public class WorkflowController {
    private final ApprovalService approvals;
    private final RuleService rules;
    private final NodeRepository nodes;
    private final PermissionService permissions;
    private final ObjectMapper json;

    public WorkflowController(ApprovalService approvals, RuleService rules, NodeRepository nodes, PermissionService permissions, ObjectMapper json) {
        this.approvals = approvals;
        this.rules = rules;
        this.nodes = nodes;
        this.permissions = permissions;
        this.json = json;
    }

    public record StepDto(UUID id, int position, String name, ApprovalStep.Decision decision, String decidedBy, Instant decidedAt, String comment, boolean active) {}

    public record ApprovalDto(UUID id, UUID nodeId, String itemName, String path, String nodeKind, Approval.Kind kind, Approval.Mode mode, String title,
                              String message, Approval.Status status, String outcome, String requestedBy, String versionLabel, Instant createdAt,
                              Instant dueAt, Instant completedAt, List<StepDto> steps, boolean canDecide, boolean canCancel) {}

    public record Decision(boolean approve, String comment) {}

    public record Reassign(UUID principalId) {}

    public record RuleRequest(String name, Boolean enabled, RuleDefinition definition) {}

    public record RuleDto(UUID id, String name, boolean enabled, RuleDefinition definition, Instant updatedAt) {}

    public record RunDto(UUID id, UUID ruleId, String path, WorkflowRun.Status status, String detail, Instant ranAt) {}

    private ApprovalDto dto(CurrentUser user, Approval a) {
        Node n = nodes.findById(a.getNodeId()).orElseThrow(() -> ApiException.notFound("Approval"));
        Set<UUID> active = new HashSet<>(approvals.activeSteps(a));
        List<StepDto> steps = approvals.steps(a).stream().map(s -> new StepDto(s.getId(), s.getPosition(), s.getPrincipalName(), s.getDecision(),
                s.getDecidedByName(), s.getDecidedAt(), s.getComment(), a.isPending() && active.contains(s.getId()))).toList();
        boolean mayCancel = a.isPending() && (user.id().equals(a.getRequestedBy()) || permissions.has(user, n, Right.MANAGE_PERMISSIONS));
        return new ApprovalDto(a.getId(), n.getId(), n.getTitle() != null ? n.getTitle() : n.getName(), n.getPath(), n.getKind().name(), a.getKind(), a.getMode(),
                a.getTitle(), a.getMessage(), a.getStatus(), a.getOutcome(), a.getRequestedByName(), a.getVersionLabel(), a.getCreatedAt(), a.getDueAt(),
                a.getCompletedAt(), steps, approvals.canDecide(user, a), mayCancel);
    }

    private List<ApprovalDto> dtos(CurrentUser user, List<Approval> list) {
        return list.stream().map(a -> dto(user, a)).toList();
    }

    @PostMapping("/nodes/{id}/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalDto request(@PathVariable UUID id, @RequestBody ApprovalService.Request body) {
        CurrentUser user = CurrentUser.get();
        return dto(user, approvals.start(user, id, body));
    }

    @GetMapping("/nodes/{id}/approvals")
    public List<ApprovalDto> forNode(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        return dtos(user, approvals.forNode(user, id));
    }

    @GetMapping("/me/approvals")
    public List<ApprovalDto> mine(@RequestParam(defaultValue = "waiting") String box) {
        CurrentUser user = CurrentUser.get();
        return dtos(user, box.equals("sent") ? approvals.sentBy(user) : approvals.waitingFor(user));
    }

    @GetMapping("/me/approvals/count")
    public Map<String, Integer> count() {
        return Map.of("waiting", approvals.waitingFor(CurrentUser.get()).size());
    }

    @GetMapping("/approvals/{id}")
    public ApprovalDto get(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        return dto(user, approvals.get(user, id));
    }

    @PostMapping("/approvals/{id}/decide")
    public ApprovalDto decide(@PathVariable UUID id, @RequestBody Decision body) {
        CurrentUser user = CurrentUser.get();
        try {
            return dto(user, approvals.decide(user, id, body.approve(), body.comment()));
        } catch (OptimisticLockingFailureException e) {
            throw ApiException.conflict("decided_elsewhere", "Someone answered at the same moment. Check the request and try again");
        }
    }

    @PostMapping("/approvals/{id}/reassign")
    public ApprovalDto reassign(@PathVariable UUID id, @RequestBody Reassign body) {
        CurrentUser user = CurrentUser.get();
        return dto(user, approvals.reassign(user, id, body.principalId()));
    }

    @PostMapping("/approvals/{id}/cancel")
    public ApprovalDto cancel(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        return dto(user, approvals.cancel(user, id));
    }

    private RuleDto dto(WorkflowRule r) {
        return new RuleDto(r.getId(), r.getName(), r.isEnabled(), json.convertValue(r.getDefinition(), RuleDefinition.class), r.getUpdatedAt());
    }

    @GetMapping("/nodes/{id}/rules")
    public List<RuleDto> rules(@PathVariable UUID id) {
        return rules.list(CurrentUser.get(), id).stream().map(this::dto).toList();
    }

    @PostMapping("/nodes/{id}/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleDto createRule(@PathVariable UUID id, @RequestBody RuleRequest body) {
        return dto(rules.create(CurrentUser.get(), id, body.name(), body.enabled() == null || body.enabled(), body.definition()));
    }

    @PutMapping("/rules/{id}")
    public RuleDto updateRule(@PathVariable UUID id, @RequestBody RuleRequest body) {
        return dto(rules.update(CurrentUser.get(), id, body.name(), body.enabled() == null || body.enabled(), body.definition()));
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID id) {
        rules.delete(CurrentUser.get(), id);
    }

    @GetMapping("/nodes/{id}/rule-runs")
    public List<RunDto> runs(@PathVariable UUID id) {
        return rules.runs(CurrentUser.get(), id).stream().map(r -> new RunDto(r.getId(), r.getRuleId(), r.getNodePath(), r.getStatus(), r.getDetail(), r.getRanAt())).toList();
    }
}
