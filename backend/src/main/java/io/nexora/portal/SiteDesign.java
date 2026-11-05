package io.nexora.portal;

import io.nexora.common.ApiException;
import io.nexora.content.SafeUrls;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A site's look: theme, header, top navigation and home page. Stored in the site node's settings, so it is
 * versioned with the site and travels with exports.
 */
public record SiteDesign(String theme, Header header, List<Link> navigation, Home home) {

    private static final int MAX_MENU_COLUMNS = 6;
    private static final int MAX_MENU_LINKS = 12;

    public static final Set<String> THEMES = Set.of("blue", "teal", "green", "purple", "red", "orange", "gray", "dark");

    public record Header(String layout, String background, boolean showTitle, boolean showLogo) {
        public static final Set<String> LAYOUTS = Set.of("compact", "standard", "extended");
        public static final Set<String> BACKGROUNDS = Set.of("none", "soft", "strong");
    }

    /** A navigation entry. With {@code groups} it opens a mega menu, and it then needs no address of its own. */
    public record Link(String label, String url, List<LinkGroup> groups) {
        public Link(String label, String url) {
            this(label, url, null);
        }
    }

    /** One column of a mega menu. */
    public record LinkGroup(String heading, List<Link> links) {}

    public record Home(String welcomeTitle, String welcome, List<Link> quickLinks, boolean showNews, UUID newsListId,
                       boolean showLibraries, boolean showLists) {}

    public static SiteDesign defaults(String siteTitle, String sitePath) {
        return new SiteDesign("blue",
                new Header("standard", "soft", true, true),
                List.of(new Link("Home", sitePath)),
                new Home("Welcome to " + siteTitle, "", List.of(), false, null, true, true));
    }

    /** Fills gaps with defaults and rejects anything unsafe or out of range. */
    public SiteDesign validated(SiteDesign fallback) {
        String t = theme == null ? fallback.theme() : theme;
        if (!THEMES.contains(t)) throw ApiException.badRequest("invalid_design", "Unknown theme '" + t + "'");

        Header h = header == null ? fallback.header() : header;
        String layout = h.layout() == null ? "standard" : h.layout();
        String bg = h.background() == null ? "soft" : h.background();
        if (!Header.LAYOUTS.contains(layout)) throw ApiException.badRequest("invalid_design", "Unknown header layout");
        if (!Header.BACKGROUNDS.contains(bg)) throw ApiException.badRequest("invalid_design", "Unknown header background");

        List<Link> nav = links(navigation == null ? fallback.navigation() : navigation, 25, "Navigation");

        Home hm = home == null ? fallback.home() : home;
        String welcomeTitle = hm.welcomeTitle() == null ? "" : hm.welcomeTitle().strip();
        String welcome = hm.welcome() == null ? "" : hm.welcome();
        if (welcomeTitle.length() > 120) throw ApiException.badRequest("invalid_design", "Welcome title is too long");
        if (welcome.length() > 5000) throw ApiException.badRequest("invalid_design", "Welcome text is too long (5000 characters)");
        List<Link> quick = links(hm.quickLinks() == null ? List.of() : hm.quickLinks(), 24, "Quick links");

        return new SiteDesign(t, new Header(layout, bg, h.showTitle(), h.showLogo()), nav,
                new Home(welcomeTitle, welcome, quick, hm.showNews(), hm.newsListId(), hm.showLibraries(), hm.showLists()));
    }

    public static List<Link> links(List<Link> in, int max, String what) {
        if (in.size() > max) throw ApiException.badRequest("invalid_design", what + ": at most " + max + " links");
        List<Link> out = new ArrayList<>();
        for (Link l : in) {
            String label = l.label() == null ? "" : l.label().strip();
            String url = l.url() == null ? "" : l.url().strip();
            if (label.isEmpty() || label.length() > 60) {
                throw ApiException.badRequest("invalid_design", what + ": every link needs a label of at most 60 characters");
            }
            List<LinkGroup> groups = groups(l.groups(), label);
            boolean menu = groups != null;
            if (!(menu && url.isEmpty()) && !SafeUrls.isSafe(url)) {
                throw ApiException.badRequest("invalid_design", what + ": '" + label + "' must link to https://..., mailto: or a /sites/... path");
            }
            out.add(new Link(label, url.isEmpty() ? null : url, groups));
        }
        return out;
    }

    private static List<LinkGroup> groups(List<LinkGroup> in, String label) {
        if (in == null || in.isEmpty()) return null;
        if (in.size() > MAX_MENU_COLUMNS) throw ApiException.badRequest("invalid_design", "'" + label + "' can have at most " + MAX_MENU_COLUMNS + " columns");
        List<LinkGroup> out = new ArrayList<>();
        for (LinkGroup g : in) {
            String heading = g.heading() == null ? "" : g.heading().strip();
            if (heading.length() > 60) throw ApiException.badRequest("invalid_design", "A menu heading can be at most 60 characters");
            List<Link> flat = new ArrayList<>();
            for (Link l : g.links() == null ? List.<Link>of() : g.links()) flat.add(new Link(l.label(), l.url())); // one level deep
            out.add(new LinkGroup(heading, links(flat, MAX_MENU_LINKS, "'" + label + "'")));
        }
        return out;
    }
}
