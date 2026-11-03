package io.nexora.content;

import java.util.List;

/** SharePoint's common list templates, provisioned as columns. Every list also has a built-in Title. */
public enum ListTemplate {
    CUSTOM("Custom list", "A blank list: add the columns you need.", List.of()),
    TASKS("Tasks", "Track work with owners, due dates and status.", List.of(
            Column.choice("Status", true, "Not started", "In progress", "Completed", "Deferred", "Waiting on someone else"),
            Column.choice("Priority", false, "(1) High", "(2) Normal", "(3) Low"),
            Column.of("Assigned to", ColumnDef.Type.PERSON),
            Column.of("Start date", ColumnDef.Type.DATE),
            Column.of("Due date", ColumnDef.Type.DATE),
            Column.of("% complete", ColumnDef.Type.NUMBER),
            Column.of("Description", ColumnDef.Type.NOTE))),
    ISSUES("Issue tracker", "Log issues and follow them to resolution.", List.of(
            Column.choice("Issue status", true, "Active", "Resolved", "Closed"),
            Column.choice("Priority", false, "(1) High", "(2) Normal", "(3) Low"),
            Column.choice("Category", false, "(1) Category1", "(2) Category2", "(3) Category3"),
            Column.of("Assigned to", ColumnDef.Type.PERSON),
            Column.of("Due date", ColumnDef.Type.DATE),
            Column.of("Description", ColumnDef.Type.NOTE))),
    CONTACTS("Contacts", "People your team works with.", List.of(
            Column.of("First name", ColumnDef.Type.TEXT),
            Column.of("Company", ColumnDef.Type.TEXT),
            Column.of("Job title", ColumnDef.Type.TEXT),
            Column.of("Email", ColumnDef.Type.TEXT),
            Column.of("Business phone", ColumnDef.Type.TEXT),
            Column.of("Mobile phone", ColumnDef.Type.TEXT),
            Column.of("Notes", ColumnDef.Type.NOTE))),
    ANNOUNCEMENTS("Announcements", "News for the site's home page.", List.of(
            Column.of("Body", ColumnDef.Type.NOTE),
            Column.of("Expires", ColumnDef.Type.DATE))),
    LINKS("Links", "Useful links for the team.", List.of(
            Column.required("URL", ColumnDef.Type.URL),
            Column.of("Notes", ColumnDef.Type.NOTE))),
    EVENTS("Events", "Meetings, deadlines and other dates.", List.of(
            Column.required("Start time", ColumnDef.Type.DATE),
            Column.of("End time", ColumnDef.Type.DATE),
            Column.of("Location", ColumnDef.Type.TEXT),
            Column.choice("Category", false, "Meeting", "Work hours", "Business", "Holiday", "Get-together", "Birthday"),
            Column.of("Description", ColumnDef.Type.NOTE)));

    public record Column(String displayName, ColumnDef.Type type, boolean required, List<String> choices) {
        static Column of(String name, ColumnDef.Type type) { return new Column(name, type, false, List.of()); }
        static Column required(String name, ColumnDef.Type type) { return new Column(name, type, true, List.of()); }
        static Column choice(String name, boolean required, String... choices) {
            return new Column(name, ColumnDef.Type.CHOICE, required, List.of(choices));
        }
    }

    private final String title;
    private final String description;
    private final List<Column> columns;

    ListTemplate(String title, String description, List<Column> columns) {
        this.title = title;
        this.description = description;
        this.columns = columns;
    }

    public String title() { return title; }
    public String description() { return description; }
    public List<Column> columns() { return columns; }
}
