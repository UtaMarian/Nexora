package io.nexora.pages;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * Rich text from the page editor is stored and served as HTML, so it is cleaned against a short allow-list:
 * paragraphs, headings, lists, quotes, emphasis and links (https, http, mailto or site-relative). Scripts,
 * styles, event handlers, images and frames never survive.
 */
public final class RichText {
    private static final String BASE = "https://nexora.invalid";

    private static final Safelist SAFE = new Safelist()
            .addTags("p", "br", "h2", "h3", "h4", "strong", "b", "em", "i", "u", "s", "ul", "ol", "li",
                    "blockquote", "code", "pre", "hr", "a")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "https", "http", "mailto")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer")
            .preserveRelativeLinks(true);

    private static final Document.OutputSettings OUT = new Document.OutputSettings().prettyPrint(false);

    private RichText() {}

    public static String sanitize(String html) {
        if (html == null || html.isBlank()) return "";
        return Jsoup.clean(html, BASE, SAFE, OUT);
    }

    public static String toPlainText(String html) {
        return html == null ? "" : Jsoup.parse(html).text();
    }
}
