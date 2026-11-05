package io.nexora.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.content.*;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.workflow.RuleDefinition.Action;
import io.nexora.workflow.RuleDefinition.Condition;
import io.nexora.workflow.RuleDefinition.Who;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Creating and checking rules. Whoever manages a library or list can automate it, and only inside its own site. */
@Service
@Transactional
public class RuleService {
    private static final int MAX_RULES = 50;
    private static final int MAX_ACTIONS = 10;
    private static final Set<String> TRIGGERS = Set.of("CREATED", "CHANGED", "APPROVED", "REJECTED", "DATE");
    private static final Set<String> OPERATORS = Set.of("eq", "ne", "contains", "gt", "lt", "empty", "notEmpty");
    private static final Set<String> BUILT_IN = Set.of("Title", "Name", "FileType", "CreatedBy", "ModifiedBy");
    private static final Set<String> LIBRARY_ONLY = Set.of("MOVE_TO_FOLDER");
    private static final Set<String> ACTIONS = Set.of("SEND_EMAIL", "SET_VALUE", "REQUEST_APPROVAL", "REQUEST_REVIEW", "MOVE_TO_FOLDER", "CREATE_ITEM");
    private static final Set<String> WHO = Set.of(Who.PERSON, Who.CREATOR, Who.EDITOR, Who.COLUMN);

    private final WorkflowRuleRepository rules;
    private final WorkflowRunRepository runs;
    private final ContentService content;
    private final NodeRepository nodes;
    private final ColumnDefRepository columns;
    private final PrincipalRepository principals;
    private final PermissionService permissions;
    private final AuditService audit;
    private final ObjectMapper json;

    public RuleService(WorkflowRuleRepository rules, WorkflowRunRepository runs, ContentService content, NodeRepository nodes,
                       ColumnDefRepository columns, PrincipalRepository principals, PermissionService permissions, AuditService audit,
                       ObjectMapper json) {
        this.rules = rules;
        this.runs = runs;
        this.content = content;
        this.nodes = nodes;
        this.columns = columns;
        this.principals = principals;
        this.permissions = permissions;
        this.audit = audit;
        this.json = json;
    }

    RuleDefinition definition(WorkflowRule rule) {
        return json.convertValue(rule.getDefinition(), RuleDefinition.class);
    }

    @Transactional(readOnly = true)
    public List<WorkflowRule> list(CurrentUser user, UUID containerId) {
        return rules.findByContainerIdOrderByCreatedAt(container(user, containerId, Right.VIEW).getId());
    }

    @Transactional(readOnly = true)
    public List<WorkflowRun> runs(CurrentUser user, UUID containerId) {
        List<UUID> ids = list(user, containerId).stream().map(WorkflowRule::getId).toList();
        return ids.isEmpty() ? List.of() : runs.findByRuleIdInOrderByRanAtDesc(ids, PageRequest.of(0, 50));
    }

    public WorkflowRule create(CurrentUser user, UUID containerId, String name, boolean enabled, RuleDefinition definition) {
        Node container = container(user, containerId, Right.MANAGE_LISTS);
        if (rules.findByContainerIdOrderByCreatedAt(containerId).size() >= MAX_RULES) {
            throw bad("A library or list can have at most " + MAX_RULES + " rules");
        }
        WorkflowRule rule = rules.save(new WorkflowRule(containerId, name(name), enabled, map(validated(container, definition)), user.id()));
        audit.record(user, "RULE_CREATE", container, Map.of("rule", rule.getName()));
        return rule;
    }

    public WorkflowRule update(CurrentUser user, UUID ruleId, String name, boolean enabled, RuleDefinition definition) {
        WorkflowRule rule = rules.findById(ruleId).orElseThrow(() -> ApiException.notFound("Rule"));
        Node container = container(user, rule.getContainerId(), Right.MANAGE_LISTS);
        rule.update(name(name), enabled, map(validated(container, definition)));
        audit.record(user, "RULE_UPDATE", container, Map.of("rule", rule.getName(), "enabled", enabled));
        return rule;
    }

    public void delete(CurrentUser user, UUID ruleId) {
        WorkflowRule rule = rules.findById(ruleId).orElseThrow(() -> ApiException.notFound("Rule"));
        Node container = container(user, rule.getContainerId(), Right.MANAGE_LISTS);
        rules.delete(rule);
        audit.record(user, "RULE_DELETE", container, Map.of("rule", rule.getName()));
    }

    private Node container(CurrentUser user, UUID id, Right right) {
        Node node = content.get(user, id);
        if (node.getKind() != Node.Kind.LIBRARY && node.getKind() != Node.Kind.LIST) throw bad("Rules belong to a library or a list");
        permissions.require(user, node, right);
        return node;
    }

    private Map<String, Object> map(RuleDefinition d) {
        return json.convertValue(d, new TypeReference<Map<String, Object>>() {});
    }

    private static String name(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 120) throw bad("A rule needs a name of at most 120 characters");
        return n;
    }

    private RuleDefinition validated(Node container, RuleDefinition d) {
        if (d == null || d.trigger() == null || !TRIGGERS.contains(d.trigger())) throw bad("Choose when the rule runs");
        Map<String, ColumnDef> defs = new HashMap<>();
        columns.findByLibraryIdOrderByPosition(container.getId()).forEach(c -> defs.put(c.getInternalName(), c));
        if (d.trigger().equals("DATE")) {
            ColumnDef date = defs.get(d.dateColumn());
            if (date == null || date.getType() != ColumnDef.Type.DATE) throw bad("Choose a date column");
            if (d.daysBefore() == null || d.daysBefore() < 0 || d.daysBefore() > 365) throw bad("Choose how many days before the date");
        }
        List<Condition> conditions = d.conditions() == null ? List.of() : d.conditions();
        for (Condition c : conditions) {
            if (!defs.containsKey(c.column()) && !BUILT_IN.contains(c.column())) throw bad("Unknown column '" + c.column() + "'");
            if (!OPERATORS.contains(c.operator())) throw bad("Unknown condition '" + c.operator() + "'");
        }
        List<Action> actions = d.actions() == null ? List.of() : d.actions();
        if (actions.isEmpty()) throw bad("Add at least one action");
        if (actions.size() > MAX_ACTIONS) throw bad("A rule can have at most " + MAX_ACTIONS + " actions");
        for (Action a : actions) action(container, a, defs);
        return new RuleDefinition(d.trigger(), d.dateColumn(), d.daysBefore(), conditions, actions);
    }

    private void action(Node container, Action a, Map<String, ColumnDef> defs) {
        if (a.type() == null || !ACTIONS.contains(a.type())) throw bad("Unknown action '" + a.type() + "'");
        if (LIBRARY_ONLY.contains(a.type()) && container.getKind() != Node.Kind.LIBRARY) throw bad("Only library files can be moved");
        switch (a.type()) {
            case "SEND_EMAIL" -> {
                people(a, defs);
                if (a.subject() == null || a.subject().isBlank() || a.subject().length() > 200) throw bad("An email needs a subject of at most 200 characters");
                if (a.body() != null && a.body().length() > 4000) throw bad("The email text is too long");
            }
            case "REQUEST_APPROVAL", "REQUEST_REVIEW" -> {
                people(a, defs);
                if (a.dueDays() != null && (a.dueDays() < 1 || a.dueDays() > 365)) throw bad("Due date must be within a year");
                if (a.mode() != null) {
                    try {
                        Approval.Mode.valueOf(a.mode());
                    } catch (IllegalArgumentException e) {
                        throw bad("Unknown approval mode");
                    }
                }
            }
            case "SET_VALUE" -> {
                ColumnDef column = defs.get(a.column());
                if (column == null) throw bad("Choose a column to set");
                if (column.getType() == ColumnDef.Type.PERSON || column.getType() == ColumnDef.Type.LOOKUP) throw bad("Rules cannot set person or lookup columns yet");
            }
            case "MOVE_TO_FOLDER" -> {
                if (a.folder() == null || a.folder().isBlank()) throw bad("Choose a folder");
                for (String part : a.folder().split("/")) if (!part.isBlank()) Names.item(part);
            }
            case "CREATE_ITEM" -> {
                if (a.title() == null || a.title().isBlank()) throw bad("A new item needs a title");
                String site = container.getPath().substring(0, container.getPath().lastIndexOf('/'));
                nodes.findLiveByPath(site + "/" + a.list()).filter(n -> n.getKind() == Node.Kind.LIST)
                        .orElseThrow(() -> bad("Choose a list in this site"));
            }
            default -> throw new IllegalStateException(a.type());
        }
    }

    private void people(Action a, Map<String, ColumnDef> defs) {
        if (a.people() == null || a.people().isEmpty()) throw bad("Choose at least one person");
        for (Who w : a.people()) {
            if (w.kind() == null || !WHO.contains(w.kind())) throw bad("Unknown recipient");
            if (w.kind().equals(Who.PERSON)) find(w.login()).orElseThrow(() -> bad("Nobody is called '" + w.login() + "'"));
            if (w.kind().equals(Who.COLUMN) && (defs.get(w.column()) == null || defs.get(w.column()).getType() != ColumnDef.Type.PERSON)) {
                throw bad("'" + w.column() + "' is not a person column");
            }
        }
    }

    Optional<Principal> find(String login) {
        return principals.findByKindAndLogin(Principal.Kind.USER, login).or(() -> principals.findByKindAndLogin(Principal.Kind.GROUP, login));
    }

    private static ApiException bad(String message) {
        return ApiException.badRequest("invalid_rule", message);
    }
}
