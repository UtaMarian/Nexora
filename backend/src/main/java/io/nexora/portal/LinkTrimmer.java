package io.nexora.portal;

import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.identity.CurrentUser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Takes out the links someone could not follow. Outside addresses stay, and so do links to nothing in particular, which
 * reveal nothing. A site address counts when its deepest existing part is visible, which also covers pages, as they are
 * not nodes. Menus and columns left empty go too.
 */
@Component
public class LinkTrimmer {
    private final NodeRepository nodes;
    private final PermissionService permissions;

    public LinkTrimmer(NodeRepository nodes, PermissionService permissions) {
        this.nodes = nodes;
        this.permissions = permissions;
    }

    /** One reader's view, so a page of links looks each place up once. */
    public final class Reader {
        private final CurrentUser user;
        private final Map<String, Boolean> seen = new HashMap<>();

        private Reader(CurrentUser user) {
            this.user = user;
        }

        public boolean canFollow(String url) {
            if (url == null || !url.startsWith("/sites/")) return true;
            return seen.computeIfAbsent(url, this::lookUp);
        }

        private boolean lookUp(String url) {
            String path = url.replaceFirst("[?#].*$", "");
            if (path.contains("%")) path = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
            while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            for (String p = path; p.startsWith("/sites/"); p = p.substring(0, p.lastIndexOf('/'))) {
                Optional<Node> node = nodes.findLiveByPath(p);
                if (node.isPresent()) return permissions.has(user, node.get(), Right.VIEW);
            }
            return true;
        }

        public List<SiteDesign.Link> trim(List<SiteDesign.Link> links) {
            List<SiteDesign.Link> out = new ArrayList<>();
            for (SiteDesign.Link l : links == null ? List.<SiteDesign.Link>of() : links) {
                String url = canFollow(l.url()) ? l.url() : null;
                List<SiteDesign.LinkGroup> groups = null;
                if (l.groups() != null) {
                    groups = new ArrayList<>();
                    for (SiteDesign.LinkGroup g : l.groups()) {
                        List<SiteDesign.Link> kept = trim(g.links());
                        if (!kept.isEmpty()) groups.add(new SiteDesign.LinkGroup(g.heading(), kept));
                    }
                    if (groups.isEmpty()) groups = null;
                }
                if (url != null || groups != null) out.add(new SiteDesign.Link(l.label(), url, groups));
            }
            return out;
        }
    }

    public Reader reader(CurrentUser user) {
        return new Reader(user);
    }

    @Transactional(readOnly = true)
    public SiteDesign trim(CurrentUser user, SiteDesign d) {
        Reader reader = reader(user);
        SiteDesign.Home h = d.home();
        return new SiteDesign(d.theme(), d.header(), reader.trim(d.navigation()),
                new SiteDesign.Home(h.welcomeTitle(), h.welcome(), reader.trim(h.quickLinks()), h.showNews(), h.newsListId(), h.showLibraries(), h.showLists()));
    }
}
