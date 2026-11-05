package io.nexora.pages;

import java.util.List;
import java.util.Map;

/** The page layouts every site starts with. Sites add their own with Save as template. */
public enum PageTemplate {
    ARTICLE("Article", "A story with a heading, text and a picture", () -> sections(
            section("oneThirdRight", "none", List.of(
                    List.of(text("a", "<h2>Add your heading</h2><p>Start with what readers most need to know. Keep paragraphs short.</p><h3>The details</h3><p>Explain the rest here.</p>")),
                    List.of(image("b"), quickLinks("c", "Related")))))),
    DEPARTMENT("Department home", "Who you are, your people, documents and news", () -> sections(
            section("three", "strong", List.of(
                    List.of(text("a", "<h2>What we do</h2><p>Say in a line what this team does.</p>")),
                    List.of(text("b", "<h2>Who it is for</h2><p>Say who should come to you.</p>")),
                    List.of(text("c", "<h2>How to reach us</h2><p>Say how to get help.</p>")))),
            section("oneThirdRight", "none", List.of(List.of(news("d")), List.of(quickLinks("e", "Quick links"), events("f")))),
            section("oneThirdLeft", "soft", List.of(List.of(people("g")), List.of(documents("h")))))),
    TEAM("Team home", "News, what is on and shared documents for a team", () -> sections(
            section("oneThirdRight", "none", List.of(List.of(news("a"), documents("b")), List.of(events("c"), people("d")))))),
    LANDING("Landing page", "A big message and three things to do next", () -> sections(
            section("one", "strong", List.of(List.of(text("a", "<h2>Your headline goes here</h2><p>One sentence on why this matters.</p>")))),
            section("three", "none", List.of(
                    List.of(text("b", "<h3>First thing</h3><p>Describe it in a line.</p>")),
                    List.of(text("c", "<h3>Second thing</h3><p>Describe it in a line.</p>")),
                    List.of(text("d", "<h3>Third thing</h3><p>Describe it in a line.</p>")))),
            section("one", "soft", List.of(List.of(quickLinks("e", "Get started"))))));

    private final String title;
    private final String description;
    private final java.util.function.Supplier<Canvas> canvas;

    PageTemplate(String title, String description, java.util.function.Supplier<Canvas> canvas) {
        this.title = title;
        this.description = description;
        this.canvas = canvas;
    }

    public String title() { return title; }
    public String description() { return description; }
    public Canvas canvas() { return canvas.get(); }

    private static Canvas sections(Canvas.Section... s) {
        return new Canvas(List.of(s));
    }

    private static Canvas.Section section(String layout, String background, List<List<Canvas.WebPart>> columns) {
        return new Canvas.Section(layout, background, columns);
    }

    private static Canvas.WebPart part(String id, String type, Map<String, Object> props) {
        return new Canvas.WebPart(type + "-" + id, type, props);
    }

    private static Canvas.WebPart text(String id, String html) { return part(id, "text", Map.of("html", html)); }
    private static Canvas.WebPart image(String id) { return part(id, "image", Map.of("alt", "", "caption", "", "size", "full")); }
    private static Canvas.WebPart news(String id) { return part(id, "news", Map.of("title", "News", "limit", 3, "layout", "cards", "scope", "site")); }
    private static Canvas.WebPart documents(String id) { return part(id, "documents", Map.of("title", "Documents", "limit", 6)); }
    private static Canvas.WebPart events(String id) { return part(id, "events", Map.of("title", "Coming up", "limit", 5)); }
    private static Canvas.WebPart people(String id) { return part(id, "people", Map.of("title", "People", "people", List.of())); }
    private static Canvas.WebPart quickLinks(String id, String title) { return part(id, "quicklinks", Map.of("title", title, "layout", "list", "links", List.of())); }
}
