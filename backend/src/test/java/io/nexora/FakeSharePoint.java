package io.nexora;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small in-process stand-in for a SharePoint Server farm: the REST endpoints NEXORA's migration reads, for one
 * site at /sites/hr. Built from Microsoft's REST documentation, so it proves NEXORA's logic, not compatibility
 * with a real farm. Content is mutable so tests can simulate changes between two runs.
 */
class FakeSharePoint implements AutoCloseable {
    static final String DOCS = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String SECRET = "aaaaaaaa-0000-0000-0000-000000000002";
    static final String TASKS = "aaaaaaaa-0000-0000-0000-000000000003";
    static final String PAGES = "aaaaaaaa-0000-0000-0000-000000000004";
    static final String REPORT = "bbbbbbbb-0000-0000-0000-000000000001";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ITEM_ROLES = Pattern.compile(".*/lists\\(guid'([^']+)'\\)/items\\((\\d+)\\)/roleassignments$");
    private static final Pattern ATTACHMENTS = Pattern.compile(".*/lists\\(guid'[^']+'\\)/items\\((\\d+)\\)/AttachmentFiles$");
    private static final String ATTACHMENT_URL = "/sites/hr/Lists/Tasks/Attachments/4/plan.txt";
    private static final String DEPARTMENTS = "eeeeeeee-0000-0000-0000-000000000009";
    private static final Pattern LIST_PART = Pattern.compile(".*/lists\\(guid'([^']+)'\\)/(fields|items|roleassignments)$");

    /** A file, with history. */
    static class SpFile {
        String id, folder, name, label, modified, content;
        List<Map<String, Object>> history = new ArrayList<>();
        Map<String, Object> fields = new LinkedHashMap<>();
        int itemId;
        boolean unique;
        String author;

        SpFile(String id, String folder, String name, String label, String modified, String content, int itemId, String author) {
            this.id = id;
            this.folder = folder;
            this.name = name;
            this.label = label;
            this.modified = modified;
            this.content = content;
            this.itemId = itemId;
            this.author = author;
        }

        String url() {
            return folder + "/" + name;
        }
    }

    final String jdoe;
    final String msmith;
    final Map<String, SpFile> files = new ConcurrentHashMap<>();
    final Map<String, String> blobs = new ConcurrentHashMap<>();
    final List<Map<String, Object>> tasks = Collections.synchronizedList(new ArrayList<>());
    final Set<String> failing = ConcurrentHashMap.newKeySet();
    private final HttpServer server;

    FakeSharePoint(String jdoeLogin, String msmithLogin) throws IOException {
        this.jdoe = "i:0#.w|contoso\\" + jdoeLogin;
        this.msmith = "i:0#.w|contoso\\" + msmithLogin;

        SpFile report = new SpFile(REPORT, "/sites/hr/Shared Documents", "Report.docx", "3.0", "2024-03-03T10:00:00Z", "report v3", 1, jdoe);
        report.history.add(version(512, "1.0", "2024-01-01T10:00:00Z", "first draft", msmith));
        report.history.add(version(1024, "2.0", "2024-02-02T10:00:00Z", "reviewed", jdoe));
        blobs.put("_vti_history/512/Shared Documents/Report.docx", "report v1");
        blobs.put("_vti_history/1024/Shared Documents/Report.docx", "report v2");
        report.fields.put("Project_x0020_Code", "P-100");
        report.fields.put("Category", "Finance");
        report.fields.put("ReviewerId", 7);
        files.put(report.id, report);

        SpFile leave = new SpFile("bbbbbbbb-0000-0000-0000-000000000002", "/sites/hr/Shared Documents/Policies", "Leave.pdf", "1.0",
                "2024-01-15T10:00:00Z", "leave policy", 2, msmith);
        leave.unique = true;
        files.put(leave.id, leave);

        files.put("bbbbbbbb-0000-0000-0000-000000000003", new SpFile("bbbbbbbb-0000-0000-0000-000000000003", "/sites/hr/Secret", "Salaries.xlsx",
                "1.0", "2024-01-20T10:00:00Z", "salaries", 1, jdoe));

        tasks.add(task(1, "Prepare review", "In progress", "2024-02-01T09:00:00Z"));
        tasks.add(task(4, "Close the books", "Not started", "2024-02-02T09:00:00Z"));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    Map<String, Object> task(int id, String title, String status, String modified) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ID", id);
        m.put("Title", title);
        m.put("TaskStatus", status);
        m.put("Created", "2024-01-01T09:00:00Z");
        m.put("Modified", modified);
        m.put("Attachments", id == 4);
        if (id == 4) m.put("DepartmentId", 12);
        m.put("Author", user(jdoe));
        m.put("Editor", user(msmith));
        return m;
    }

    private Map<String, Object> version(int id, String label, String created, String comment, String by) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ID", id);
        m.put("VersionLabel", label);
        m.put("Created", created);
        m.put("CheckInComment", comment);
        m.put("Url", "_vti_history/" + id + "/Shared Documents/Report.docx");
        m.put("CreatedBy", user(by));
        return m;
    }

    private static Map<String, Object> user(String login) {
        return Map.of("LoginName", login, "Title", login, "Email", "");
    }

    private void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        String query = Objects.toString(x.getRequestURI().getQuery(), "");
        String auth = x.getRequestHeaders().getFirst("Authorization");
        String expected = "Basic " + Base64.getEncoder().encodeToString("svc:p4ss".getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(auth)) {
            send(x, 401, "{}");
            return;
        }
        for (String f : failing) {
            if ((path + "?" + query).contains(f)) {
                send(x, 500, "{\"odata.error\":{\"message\":{\"value\":\"boom\"}}}");
                return;
            }
        }
        if (path.contains("/_vti_history/")) {
            String key = path.substring(path.indexOf("_vti_history"));
            send(x, blobs.containsKey(key) ? 200 : 404, blobs.getOrDefault(key, ""));
            return;
        }
        Object body = route(path, query);
        if (body instanceof byte[] raw) {
            x.sendResponseHeaders(200, raw.length);
            x.getResponseBody().write(raw);
            x.close();
        } else if (body == null) {
            send(x, 404, "{}");
        } else {
            send(x, 200, JSON.writeValueAsString(body));
        }
    }

    private Object route(String path, String query) {
        String u = alias(query);
        if (path.endsWith("/_api/search/query")) return search();
        if (!path.startsWith("/sites/hr/_api/web") && !path.equals("/_api/web")) return null;
        if (path.equals("/_api/web")) return Map.of("Title", "Root", "ServerRelativeUrl", "/", "UIVersion", 16);
        if (path.endsWith("/_api/web")) {
            return Map.of("Id", "cccccccc-0000-0000-0000-000000000001", "Title", "Human Resources", "Description", "HR team",
                    "ServerRelativeUrl", "/sites/hr", "Created", "2020-01-01T00:00:00Z", "WebTemplate", "STS", "Configuration", 0,
                    "LastItemModifiedDate", "2024-03-03T10:00:00Z", "UIVersion", 16);
        }
        if (path.endsWith("/webs")) return value(List.of(Map.of("Title", "Archive", "ServerRelativeUrl", "/sites/hr/archive")));
        if (path.endsWith("/_api/web/lists")) return value(lists());
        if (path.endsWith("/associatedownergroup")) return Map.of("Id", 1, "Title", "HR Owners");
        if (path.endsWith("/associatedmembergroup")) return Map.of("Id", 2, "Title", "HR Members");
        if (path.endsWith("/associatedvisitorgroup")) return Map.of("Id", 3, "Title", "HR Visitors");
        if (path.endsWith("/_api/web/roleassignments")) {
            return value(List.of(ra(group(1, "HR Owners"), 5), ra(group(2, "HR Members"), 6), ra(group(3, "HR Visitors"), 2),
                    ra(group(9, "Auditors"), 2), ra(Map.of("PrincipalType", 1, "LoginName", "i:0#.w|contoso\\ghost", "Title", "Ghost", "Id", 50), 3)));
        }
        Matcher sg = Pattern.compile(".*/sitegroups/getbyid\\((\\d+)\\)/users$").matcher(path);
        if (sg.matches()) {
            int id = Integer.parseInt(sg.group(1));
            return value(id == 1 ? List.of(member(jdoe)) : id == 2 ? List.of(member(msmith)) : id == 9 ? List.of(member(msmith)) : List.of());
        }
        if (path.contains("/getuserbyid(")) return Map.of("LoginName", msmith, "Title", "M Smith", "Email", "", "PrincipalType", 1);

        Matcher ir = ITEM_ROLES.matcher(path);
        if (ir.matches()) return value(List.of(ra(member(jdoe), 3)));
        Matcher at = ATTACHMENTS.matcher(path);
        if (at.matches()) return value(List.of(Map.of("FileName", "plan.txt", "ServerRelativeUrl", ATTACHMENT_URL)));
        Matcher lp = LIST_PART.matcher(path);
        if (lp.matches()) return listPart(lp.group(1), lp.group(2));

        if (path.endsWith("/GetFolderByServerRelativeUrl(@u)")) return folder(u);
        if (path.endsWith("/GetFileByServerRelativeUrl(@u)/Versions")) {
            return value(byUrl(u).map(f -> f.history).orElse(new ArrayList<>()));
        }
        if (path.endsWith("/GetFileByServerRelativeUrl(@u)/$value")) {
            if (ATTACHMENT_URL.equals(u)) return "attached plan".getBytes(StandardCharsets.UTF_8);
            return byUrl(u).map(f -> f.content.getBytes(StandardCharsets.UTF_8)).orElse(null);
        }
        return null;
    }

    private List<Map<String, Object>> lists() {
        return List.of(list(DOCS, "Documents", 101, "Shared Documents", "/sites/hr/Shared Documents", 2, false),
                list(SECRET, "Secret", 101, "Secret", "/sites/hr/Secret", 1, true),
                list(TASKS, "Tasks", 107, "Tasks", "/sites/hr/Lists/Tasks", tasks.size(), false),
                list(DEPARTMENTS, "Departments", 100, "Departments", "/sites/hr/Lists/Departments", 1, false),
                list(PAGES, "Site Pages", 119, "SitePages", "/sites/hr/SitePages", 3, false));
    }

    private static Map<String, Object> list(String id, String title, int template, String name, String url, int count, boolean unique) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Id", id);
        m.put("Title", title);
        m.put("Description", "");
        m.put("BaseTemplate", template);
        m.put("ItemCount", count);
        m.put("HasUniqueRoleAssignments", unique);
        m.put("EnableVersioning", true);
        m.put("EnableMinorVersions", false);
        m.put("RootFolder", Map.of("Name", name, "ServerRelativeUrl", url));
        return m;
    }

    private Object listPart(String id, String part) {
        if (part.equals("roleassignments")) return value(List.of(ra(member(jdoe), 5)));
        if (part.equals("fields")) {
            if (id.equals(TASKS)) {
                return value(List.of(Map.of("InternalName", "TaskStatus", "Title", "Task Status", "TypeAsString", "Choice",
                        "Required", false, "Choices", List.of("Not started", "In progress")),
                        Map.of("InternalName", "Department", "Title", "Department", "TypeAsString", "Lookup", "Required", false,
                                "LookupList", "{" + DEPARTMENTS.toUpperCase(Locale.ROOT) + "}")));
            }
            if (!id.equals(DOCS)) return value(List.of());
            return value(List.of(
                    Map.of("InternalName", "Project_x0020_Code", "Title", "Project Code", "TypeAsString", "Text", "Required", true),
                    Map.of("InternalName", "Category", "Title", "Category", "TypeAsString", "Choice", "Required", false, "Choices", List.of("HR")),
                    Map.of("InternalName", "Reviewer", "Title", "Reviewer", "TypeAsString", "User", "Required", false),
                    Map.of("InternalName", "Linked", "Title", "Linked", "TypeAsString", "Lookup", "Required", false)));
        }
        if (id.equals(TASKS)) return value(new ArrayList<>(tasks));
        if (id.equals(DEPARTMENTS)) {
            return value(List.of(Map.of("ID", 12, "Title", "Finance", "Created", "2024-01-01T09:00:00Z", "Modified", "2024-01-01T09:00:00Z",
                    "Attachments", false, "Author", user(jdoe), "Editor", user(jdoe))));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SpFile f : files.values()) {
            if (id.equals(DOCS) ? f.folder.startsWith("/sites/hr/Shared Documents") : f.folder.startsWith("/sites/hr/Secret")) {
                out.add(Map.of("Id", f.itemId, "FileRef", f.url(), "HasUniqueRoleAssignments", f.unique));
            }
        }
        return value(out);
    }

    private Object folder(String url) {
        List<Map<String, Object>> folders = new ArrayList<>();
        if (url.equals("/sites/hr/Shared Documents")) {
            folders.add(folderRow("Forms", url + "/Forms", "dddddddd-0000-0000-0000-000000000001"));
            folders.add(folderRow("Policies", url + "/Policies", "dddddddd-0000-0000-0000-000000000002"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SpFile f : files.values()) {
            if (!f.folder.equals(url)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("UniqueId", f.id);
            m.put("Name", f.name);
            m.put("ServerRelativeUrl", f.url());
            m.put("Length", f.content.length());
            m.put("TimeCreated", "2024-01-01T10:00:00Z");
            m.put("TimeLastModified", f.modified);
            m.put("UIVersionLabel", f.label);
            m.put("CheckInComment", "current");
            m.put("CheckOutType", 2);
            m.put("Author", user(f.author));
            m.put("ModifiedBy", user(f.author));
            m.put("ListItemAllFields", f.fields);
            rows.add(m);
        }
        return Map.of("Folders", folders, "Files", rows);
    }

    private static Map<String, Object> folderRow(String name, String url, String id) {
        return Map.of("Name", name, "ServerRelativeUrl", url, "UniqueId", id, "TimeCreated", "2024-01-01T10:00:00Z",
                "TimeLastModified", "2024-01-01T10:00:00Z");
    }

    private Optional<SpFile> byUrl(String url) {
        return files.values().stream().filter(f -> f.url().equals(url)).findFirst();
    }

    private Object search() {
        Map<String, Object> row = Map.of("Cells", List.of(Map.of("Key", "Title", "Value", "Human Resources"),
                Map.of("Key", "Path", "Value", url() + "/sites/hr")));
        return Map.of("PrimaryQueryResult", Map.of("RelevantResults", Map.of("Table", Map.of("Rows", List.of(row)))));
    }

    private static Map<String, Object> group(int id, String title) {
        return Map.of("Id", id, "PrincipalType", 8, "Title", title, "LoginName", title);
    }

    private static Map<String, Object> member(String login) {
        return Map.of("PrincipalType", 1, "LoginName", login, "Title", login, "Email", "", "Id", 20);
    }

    private static Map<String, Object> ra(Map<String, Object> member, int roleKind) {
        return Map.of("Member", member, "RoleDefinitionBindings", List.of(Map.of("Name", "role", "RoleTypeKind", roleKind)));
    }

    private static Map<String, Object> value(Object rows) {
        return Map.of("value", rows);
    }

    /** The decoded @u parameter alias. */
    private static String alias(String query) {
        Matcher m = Pattern.compile("@u='(.*?)'(&|$)").matcher(query);
        return m.find() ? m.group(1).replace("''", "'") : "";
    }

    private static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
