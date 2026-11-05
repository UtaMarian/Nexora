package io.nexora.lists;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.content.ColumnDef;
import io.nexora.content.ColumnDefRepository;
import io.nexora.content.ContentService;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.identity.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Saved list views: which columns, order and filter to show, as a table, calendar or gallery. They live in the list's
 * settings, so they travel with site exports. The web app applies them to the items it has loaded.
 */
@Service
@Transactional
public class ListViewService {
    private static final int MAX_VIEWS = 30;
    private static final String SETTING = "views";
    private static final Set<String> TYPES = Set.of("table", "calendar", "gallery");
    private static final Set<String> OPERATORS = Set.of("eq", "ne", "contains", "gt", "lt", "empty", "notEmpty");
    private static final Set<String> BUILT_IN = Set.of("Title", "Modified", "ModifiedBy", "Created", "CreatedBy");

    public record Filter(String column, String operator, String value) {}

    /**
     * @param dateColumn the calendar's start date
     * @param endColumn  the calendar's optional end date
     */
    public record View(String id, String name, String type, List<String> columns, String sortBy, boolean sortDescending,
                       List<Filter> filters, String dateColumn, String endColumn, boolean isDefault) {}

    private final ContentService content;
    private final NodeRepository nodes;
    private final ColumnDefRepository columns;
    private final PermissionService permissions;
    private final AuditService audit;
    private final ObjectMapper json;

    public ListViewService(ContentService content, NodeRepository nodes, ColumnDefRepository columns,
                           PermissionService permissions, AuditService audit, ObjectMapper json) {
        this.content = content;
        this.nodes = nodes;
        this.columns = columns;
        this.permissions = permissions;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<View> list(CurrentUser user, UUID listId) {
        return read(list(user, listId, Right.VIEW));
    }

    public View create(CurrentUser user, UUID listId, View view) {
        Node list = list(user, listId, Right.MANAGE_LISTS);
        List<View> views = read(list);
        if (views.size() >= MAX_VIEWS) throw ApiException.badRequest("too_many_views", "A list can have " + MAX_VIEWS + " views");
        View saved = validated(list, view, UUID.randomUUID().toString(), views);
        views.add(saved);
        write(list, views, saved);
        audit.record(user, "LIST_VIEW_SAVE", list, Map.of("view", saved.name()));
        return saved;
    }

    public View update(CurrentUser user, UUID listId, String viewId, View view) {
        Node list = list(user, listId, Right.MANAGE_LISTS);
        List<View> views = read(list);
        int at = indexOf(views, viewId);
        List<View> others = new ArrayList<>(views);
        others.remove(at);
        View saved = validated(list, view, viewId, others);
        views.set(at, saved);
        write(list, views, saved);
        audit.record(user, "LIST_VIEW_SAVE", list, Map.of("view", saved.name()));
        return saved;
    }

    public void delete(CurrentUser user, UUID listId, String viewId) {
        Node list = list(user, listId, Right.MANAGE_LISTS);
        List<View> views = read(list);
        View removed = views.remove(indexOf(views, viewId));
        write(list, views, null);
        audit.record(user, "LIST_VIEW_DELETE", list, Map.of("view", removed.name()));
    }

    private Node list(CurrentUser user, UUID listId, Right right) {
        Node list = content.get(user, listId);
        if (list.getKind() != Node.Kind.LIST) throw ApiException.badRequest("not_a_list", "Not a list");
        permissions.require(user, list, right);
        return list;
    }

    private static int indexOf(List<View> views, String id) {
        for (int i = 0; i < views.size(); i++) if (views.get(i).id().equals(id)) return i;
        throw ApiException.notFound("View");
    }

    private List<View> read(Node list) {
        Object raw = list.getSettings().get(SETTING);
        if (raw == null) return new ArrayList<>();
        return new ArrayList<>(json.convertValue(raw, new TypeReference<List<View>>() {}));
    }

    /** Only one view can be the default, so a new default clears the old one. */
    private void write(Node list, List<View> views, View saved) {
        List<View> out = new ArrayList<>();
        for (View v : views) {
            boolean clear = saved != null && saved.isDefault() && v.isDefault() && !v.id().equals(saved.id());
            out.add(clear ? new View(v.id(), v.name(), v.type(), v.columns(), v.sortBy(), v.sortDescending(), v.filters(),
                    v.dateColumn(), v.endColumn(), false) : v);
        }
        Map<String, Object> settings = new HashMap<>(list.getSettings());
        settings.put(SETTING, json.convertValue(out, new TypeReference<List<Map<String, Object>>>() {}));
        list.setSettings(settings);
        nodes.save(list);
    }

    private View validated(Node list, View v, String id, List<View> others) {
        String name = v.name() == null ? "" : v.name().strip();
        if (name.isEmpty() || name.length() > 60) throw bad("A view needs a name of at most 60 characters");
        if (others.stream().anyMatch(o -> o.name().equalsIgnoreCase(name))) {
            throw ApiException.conflict("name_conflict", "A view named '" + name + "' already exists");
        }
        String type = v.type() == null ? "table" : v.type();
        if (!TYPES.contains(type)) throw bad("Unknown view type '" + type + "'");

        Map<String, ColumnDef> defs = new HashMap<>();
        columns.findByLibraryIdOrderByPosition(list.getId()).forEach(c -> defs.put(c.getInternalName(), c));
        List<String> shown = v.columns() == null ? List.of() : v.columns();
        shown.forEach(c -> known(defs, c));
        if (v.sortBy() != null && !v.sortBy().isBlank()) known(defs, v.sortBy());
        List<Filter> filters = v.filters() == null ? List.of() : v.filters();
        for (Filter f : filters) {
            known(defs, f.column());
            if (!OPERATORS.contains(f.operator())) throw bad("Unknown filter '" + f.operator() + "'");
        }
        String dateColumn = blankToNull(v.dateColumn());
        String endColumn = blankToNull(v.endColumn());
        if (type.equals("calendar")) {
            if (dateColumn == null) throw bad("A calendar view needs a date column");
            date(defs, dateColumn);
            if (endColumn != null) date(defs, endColumn);
        }
        return new View(id, name, type, shown, blankToNull(v.sortBy()), v.sortDescending(), filters, dateColumn, endColumn, v.isDefault());
    }

    private static void known(Map<String, ColumnDef> defs, String column) {
        if (!defs.containsKey(column) && !BUILT_IN.contains(column)) throw bad("Unknown column '" + column + "'");
    }

    private static void date(Map<String, ColumnDef> defs, String column) {
        ColumnDef def = defs.get(column);
        if (def == null || def.getType() != ColumnDef.Type.DATE) throw bad("'" + column + "' is not a date column");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static ApiException bad(String message) {
        return ApiException.badRequest("invalid_view", message);
    }
}
