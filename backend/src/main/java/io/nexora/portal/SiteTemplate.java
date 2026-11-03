package io.nexora.portal;

import io.nexora.content.ListTemplate;

import java.util.List;

/**
 * What a new site starts with. Every site gets Owners/Members/Visitors and a Documents library; templates add
 * libraries, lists, navigation, a theme and a home page.
 */
public enum SiteTemplate {
    TEAM("Team site", "Collaborate on documents and lists with your team.",
            "blue", "standard", "soft",
            List.of(),
            List.of(),
            List.of("Documents"),
            false),
    COMMUNICATION("Communication site", "Share news, events and information with a broad audience.",
            "teal", "extended", "strong",
            List.of(),
            List.of(new ListSpec("Announcements", "News", ListTemplate.ANNOUNCEMENTS),
                    new ListSpec("Events", "Events", ListTemplate.EVENTS)),
            List.of("News", "Events", "Documents"),
            true),
    PROJECT("Project site", "Plan and track a project: tasks, issues, calendar and deliverables.",
            "purple", "standard", "soft",
            List.of(new LibrarySpec("Deliverables", "Deliverables")),
            List.of(new ListSpec("Tasks", "Tasks", ListTemplate.TASKS),
                    new ListSpec("Issues", "Issues", ListTemplate.ISSUES),
                    new ListSpec("Calendar", "Calendar", ListTemplate.EVENTS)),
            List.of("Tasks", "Issues", "Calendar", "Documents", "Deliverables"),
            false),
    INTRANET("Intranet portal", "The front door of your organization: news, policies, forms, links and contacts.",
            "green", "extended", "strong",
            List.of(new LibrarySpec("Policies", "Policies and procedures"),
                    new LibrarySpec("Templates", "Forms and templates")),
            List.of(new ListSpec("Announcements", "News", ListTemplate.ANNOUNCEMENTS),
                    new ListSpec("Events", "Events", ListTemplate.EVENTS),
                    new ListSpec("QuickLinks", "Quick links", ListTemplate.LINKS),
                    new ListSpec("Contacts", "Key contacts", ListTemplate.CONTACTS)),
            List.of("News", "Events", "Policies and procedures", "Forms and templates", "Key contacts"),
            true);

    public record LibrarySpec(String name, String title) {}
    public record ListSpec(String name, String title, ListTemplate template) {}

    private final String title;
    private final String description;
    final String theme;
    final String headerLayout;
    final String headerBackground;
    final List<LibrarySpec> libraries;
    final List<ListSpec> lists;
    /** Titles of containers to put in the top navigation, in order. */
    final List<String> navigation;
    /** Show the Announcements list as news on the home page. */
    final boolean news;

    SiteTemplate(String title, String description, String theme, String headerLayout, String headerBackground,
                 List<LibrarySpec> libraries, List<ListSpec> lists, List<String> navigation, boolean news) {
        this.title = title;
        this.description = description;
        this.theme = theme;
        this.headerLayout = headerLayout;
        this.headerBackground = headerBackground;
        this.libraries = libraries;
        this.lists = lists;
        this.navigation = navigation;
        this.news = news;
    }

    public String title() { return title; }
    public String description() { return description; }
}
