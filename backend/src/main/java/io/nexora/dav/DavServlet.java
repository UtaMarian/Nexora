package io.nexora.dav;

import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.common.ApiException;
import io.nexora.content.*;
import io.nexora.identity.CurrentUser;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriUtils;
import org.w3c.dom.Document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebDAV (RFC 4918, class 1 and 2) over the content tree, for desktop Office ({@code ms-word:ofe|u|...}) and
 * mapped network drives. The namespace mirrors SharePoint URLs:
 * {@code /dav/sites/<site>/<library>/<folder>/<file>}.
 *
 * <p>Everything goes through the same services as the REST API, so permissions, versioning, check-out and the
 * audit log apply unchanged. LOCK maps to {@link EditLockService}, which the browser editor and uploads respect.
 */
public class DavServlet extends HttpServlet {
    private static final Logger log = LoggerFactory.getLogger(DavServlet.class);
    static final String PREFIX = "/dav";
    private static final String ALLOW =
            "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK";
    private static final Pattern LOCK_TOKEN = Pattern.compile("<(opaquelocktoken:[^>]+)>");

    private final ContentService content;
    private final DocumentService documents;
    private final EditLockService locks;
    private final PermissionService permissions;
    private final GhostStore ghosts;

    public DavServlet(ContentService content, DocumentService documents, EditLockService locks,
                      PermissionService permissions, GhostStore ghosts) {
        this.content = content;
        this.documents = documents;
        this.locks = locks;
        this.permissions = permissions;
        this.ghosts = ghosts;
    }

    // ---- targets -------------------------------------------------------------------------------

    private sealed interface Target permits Root, SitesRoot, Real, GhostTarget, Missing {}
    private record Root() implements Target {}
    private record SitesRoot() implements Target {}
    private record Real(Node node) implements Target {}
    private record GhostTarget(GhostStore.Ghost ghost) implements Target {}
    private record Missing(String path) implements Target {}

    private Target resolve(CurrentUser user, String path) {
        if (path.isEmpty() || path.equals("/")) return new Root();
        if (path.equalsIgnoreCase("/sites")) return new SitesRoot();
        if (!path.toLowerCase(Locale.ROOT).startsWith("/sites/")) return new Missing(path);
        if (GhostStore.isGhostName(name(path))) {
            return ghosts.get(user.id(), path).<Target>map(GhostTarget::new).orElse(new Missing(path));
        }
        try {
            Node n = content.resolveDecoded(user, path);
            if (!isDavVisible(n)) return new Missing(path);   // lists are not files
            if (n.getKind() == Node.Kind.FILE && ghosts.isAliasedAway(user.id(), n.getId())) return new Missing(path);
            return new Real(n);
        } catch (ApiException e) {
            if (e.status().value() == 404) return new Missing(path);
            throw e;
        }
    }

    // ---- dispatch ------------------------------------------------------------------------------

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String method = req.getMethod();
        try {
            if (method.equals("OPTIONS")) {
                options(res);
                return;
            }
            CurrentUser user = CurrentUser.get();
            String path = davPath(req);
            switch (method) {
                case "PROPFIND" -> propfind(user, path, req, res);
                case "PROPPATCH" -> proppatch(user, path, req, res);
                case "GET" -> get(user, path, res, true);
                case "HEAD" -> get(user, path, res, false);
                case "PUT" -> put(user, path, req, res);
                case "DELETE" -> delete(user, path, res);
                case "MKCOL" -> mkcol(user, path, req, res);
                case "MOVE" -> moveOrCopy(user, path, req, res, true);
                case "COPY" -> moveOrCopy(user, path, req, res, false);
                case "LOCK" -> lock(user, path, req, res);
                case "UNLOCK" -> unlock(user, path, req, res);
                default -> {
                    res.setHeader("Allow", ALLOW);
                    fail(res, 405);
                }
            }
        } catch (ApiException e) {
            int status = switch (e.status().value()) {
                case 400 -> e.code().equals("invalid_name") ? 400 : 409;
                default -> e.status().value();
            };
            if (status >= 500) log.warn("WebDAV {} {} failed: {}", method, req.getRequestURI(), e.getMessage());
            if (!res.isCommitted()) fail(res, status, e.getMessage());
        }
    }

    private void options(HttpServletResponse res) {
        res.setHeader("Allow", ALLOW);
        res.setHeader("DAV", "1, 2");
        res.setHeader("MS-Author-Via", "DAV");
        res.setStatus(200);
    }

    // ---- PROPFIND ------------------------------------------------------------------------------

    private void propfind(CurrentUser user, String path, HttpServletRequest req, HttpServletResponse res)
            throws IOException {
        Target t = resolve(user, path);
        if (t instanceof Missing) {
            fail(res, 404);
            return;
        }
        boolean children = !"0".equals(req.getHeader("Depth"));
        StringBuilder xml = new StringBuilder(DavXml.MULTISTATUS_OPEN);
        switch (t) {
            case Root r -> {
                collection(xml, PREFIX + "/", "NEXORA", Instant.now());
                if (children) collection(xml, PREFIX + "/sites/", "sites", Instant.now());
            }
            case SitesRoot s -> {
                collection(xml, PREFIX + "/sites/", "sites", Instant.now());
                if (children) {
                    for (Node site : content.visibleSites(user)) {
                        if (site.getParentId() == null) node(xml, user, site, hrefFor(site.getPath(), true));
                    }
                }
            }
            case Real r -> {
                Node n = r.node();
                node(xml, user, n, hrefFor(n.getPath(), n.getKind().isContainer()));
                if (children && n.getKind().isContainer()) {
                    for (Node c : content.children(user, n.getId())) {
                        if (!isDavVisible(c)) continue;
                        if (c.getKind() == Node.Kind.FILE && ghosts.isAliasedAway(user.id(), c.getId())) continue;
                        node(xml, user, c, hrefFor(c.getPath(), c.getKind().isContainer()));
                    }
                    for (GhostStore.Ghost g : ghosts.list(user.id(), n.getPath())) ghost(xml, user, g);
                }
            }
            case GhostTarget g -> ghost(xml, user, g.ghost());
            case Missing m -> throw new IllegalStateException();
        }
        xml.append(DavXml.MULTISTATUS_CLOSE);
        multistatus(res, xml.toString());
    }

    private void collection(StringBuilder xml, String href, String name, Instant modified) {
        xml.append("<D:response><D:href>").append(DavXml.esc(href)).append("</D:href><D:propstat><D:prop>")
                .append("<D:displayname>").append(DavXml.esc(name)).append("</D:displayname>")
                .append("<D:resourcetype><D:collection/></D:resourcetype>")
                .append("<D:getlastmodified>").append(DavXml.rfc1123(modified)).append("</D:getlastmodified>")
                .append("<D:creationdate>").append(DavXml.iso(modified)).append("</D:creationdate>")
                .append("<Z:Win32FileAttributes>00000010</Z:Win32FileAttributes>")
                .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n");
    }

    private void node(StringBuilder xml, CurrentUser user, Node n, String href) {
        if (n.getKind().isContainer()) {
            collection(xml, href, n.getTitle() != null && n.getKind() == Node.Kind.SITE ? n.getName() : n.getName(),
                    n.getModifiedAt());
            return;
        }
        boolean ownDraft = n.isCheckedOut() && user.id().equals(n.getCheckedOutBy()) && n.getWorkingStorageKey() != null;
        long size = ownDraft ? n.getWorkingSizeBytes() : (n.getSizeBytes() == null ? 0 : n.getSizeBytes());
        String etag = ownDraft ? n.getWorkingSha256() : etagOf(n);
        boolean writable = permissions.has(user, n, Right.EDIT_ITEMS);
        xml.append("<D:response><D:href>").append(DavXml.esc(href)).append("</D:href><D:propstat><D:prop>")
                .append("<D:displayname>").append(DavXml.esc(n.getName())).append("</D:displayname>")
                .append("<D:resourcetype/>")
                .append("<D:getcontentlength>").append(size).append("</D:getcontentlength>")
                .append("<D:getcontenttype>").append(DavXml.esc(n.getMimeType())).append("</D:getcontenttype>")
                .append("<D:getlastmodified>").append(DavXml.rfc1123(n.getModifiedAt())).append("</D:getlastmodified>")
                .append("<D:creationdate>").append(DavXml.iso(n.getCreatedAt())).append("</D:creationdate>")
                .append("<D:getetag>\"").append(DavXml.esc(etag)).append("\"</D:getetag>")
                .append("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope>")
                .append("<D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>")
                .append("<D:lockdiscovery>");
        locks.active(n).ifPresent(l -> {
            if (l.getOwnerId().equals(user.id())) {
                xml.append(DavXml.activeLock(l.getToken(), l.getOwnerInfo(),
                        Math.max(1, Duration.between(Instant.now(), l.getExpiresAt()).toSeconds()), href));
            } else {
                // others see that it is locked, but never the token
                xml.append("<D:activelock><D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>")
                        .append("<D:depth>0</D:depth></D:activelock>");
            }
        });
        xml.append("</D:lockdiscovery>")
                .append("<Z:Win32FileAttributes>").append(writable ? "00000020" : "00000021").append("</Z:Win32FileAttributes>")
                .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n");
    }

    private void ghost(StringBuilder xml, CurrentUser user, GhostStore.Ghost g) {
        long size = g.size();
        if (g.isAlias()) {
            try {
                size = Optional.ofNullable(content.get(user, g.aliasOf()).getSizeBytes()).orElse(0L);
            } catch (ApiException ignored) {
                // the aliased document went away; show the ghost as empty
            }
        }
        xml.append("<D:response><D:href>").append(DavXml.esc(hrefFor(g.path(), false))).append("</D:href>")
                .append("<D:propstat><D:prop>")
                .append("<D:displayname>").append(DavXml.esc(name(g.path()))).append("</D:displayname>")
                .append("<D:resourcetype/>")
                .append("<D:getcontentlength>").append(size).append("</D:getcontentlength>")
                .append("<D:getlastmodified>").append(DavXml.rfc1123(g.modified())).append("</D:getlastmodified>")
                .append("<D:creationdate>").append(DavXml.iso(g.modified())).append("</D:creationdate>")
                .append("<Z:Win32FileAttributes>00000022</Z:Win32FileAttributes>") // hidden + archive
                .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n");
    }

    /** Windows sets file times and attributes; NEXORA keeps its own, so accept and ignore. */
    private void proppatch(CurrentUser user, String path, HttpServletRequest req, HttpServletResponse res)
            throws IOException {
        Target t = resolve(user, path);
        if (t instanceof Missing) {
            fail(res, 404);
            return;
        }
        Document doc = DavXml.parse(readSmall(req));
        StringBuilder xml = new StringBuilder(DavXml.MULTISTATUS_OPEN)
                .append("<D:response><D:href>").append(DavXml.esc(req.getRequestURI())).append("</D:href>")
                .append("<D:propstat><D:prop>");
        for (String[] p : DavXml.proppatchNames(doc)) {
            xml.append("<x:").append(p[1]).append(" xmlns:x=\"").append(DavXml.esc(p[0])).append("\"/>");
        }
        xml.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
                .append(DavXml.MULTISTATUS_CLOSE);
        multistatus(res, xml.toString());
    }

    // ---- GET / PUT / DELETE / MKCOL ------------------------------------------------------------

    private void get(CurrentUser user, String path, HttpServletResponse res, boolean body) throws IOException {
        Target t = resolve(user, path);
        switch (t) {
            case Missing m -> fail(res, 404);
            case Root r -> listing(res, body, "NEXORA", List.of("sites/"));
            case SitesRoot s -> listing(res, body, "Sites",
                    content.visibleSites(user).stream().filter(n -> n.getParentId() == null).map(n -> n.getName() + "/").toList());
            case GhostTarget g -> {
                if (g.ghost().isAlias()) {
                    stream(res, documents.open(user, g.ghost().aliasOf(), null), body);
                } else {
                    res.setContentType("application/octet-stream");
                    res.setContentLengthLong(g.ghost().size());
                    if (body) Files.copy(g.ghost().data(), res.getOutputStream());
                }
            }
            case Real r -> {
                if (r.node().getKind().isContainer()) {
                    listing(res, body, r.node().getName(), content.children(user, r.node().getId()).stream()
                            .map(n -> n.getName() + (n.getKind().isContainer() ? "/" : "")).toList());
                } else {
                    stream(res, documents.open(user, r.node().getId(), null), body);
                }
            }
        }
    }

    private void stream(HttpServletResponse res, DocumentService.Download d, boolean body) throws IOException {
        try (InputStream in = d.stream()) {
            res.setContentType(d.mimeType() == null ? "application/octet-stream" : d.mimeType());
            res.setContentLengthLong(d.sizeBytes());
            res.setHeader("ETag", "\"" + d.sha256() + "\"");
            if (body) in.transferTo(res.getOutputStream());
        }
    }

    private void listing(HttpServletResponse res, boolean body, String title, List<String> names) throws IOException {
        StringBuilder html = new StringBuilder("<!doctype html><meta charset=utf-8><title>")
                .append(DavXml.esc(title)).append("</title><h1>").append(DavXml.esc(title)).append("</h1><ul>");
        for (String n : names) {
            html.append("<li><a href=\"").append(UriUtils.encodePathSegment(n.replace("/", ""), StandardCharsets.UTF_8))
                    .append(n.endsWith("/") ? "/" : "").append("\">").append(DavXml.esc(n)).append("</a></li>");
        }
        html.append("</ul>");
        byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8);
        res.setContentType("text/html;charset=utf-8");
        res.setContentLength(bytes.length);
        if (body) res.getOutputStream().write(bytes);
    }

    private void put(CurrentUser user, String path, HttpServletRequest req, HttpServletResponse res) throws IOException {
        Path tmp = spool(req);
        try {
            if (GhostStore.isGhostName(name(path))) {
                if (resolveParent(user, path) == null) {
                    fail(res, 409);
                    return;
                }
                boolean existed = ghosts.get(user.id(), path).isPresent();
                ghosts.putData(user.id(), path, tmp);
                res.setStatus(existed ? 204 : 201);
                return;
            }
            Node existing = existingFile(user, path);
            if (existing != null) {
                documents.saveContent(user, existing.getId(), req.getContentType(), tmp, null);
                ghosts.dropAliasesOf(user.id(), existing.getId());
                res.setStatus(204);
                return;
            }
            Node parent = resolveParent(user, path);
            if (parent == null) {
                fail(res, 409, "Parent folder does not exist");
                return;
            }
            documents.upload(user, parent.getId(), name(path), req.getContentType(), tmp, false, null);
            res.setStatus(201);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void delete(CurrentUser user, String path, HttpServletResponse res) throws IOException {
        if (GhostStore.isGhostName(name(path))) {
            // temp files may already be gone (e.g. folded into a new version by the safe-save pattern)
            ghosts.remove(user.id(), path);
            res.setStatus(204);
            return;
        }
        Target t = resolve(user, path);
        switch (t) {
            case GhostTarget g -> {
                ghosts.remove(user.id(), path);
                res.setStatus(204);
            }
            case Real r -> {
                if (r.node().getKind() == Node.Kind.SITE) {
                    fail(res, 403, "Sites cannot be deleted over WebDAV");
                    return;
                }
                content.delete(user, r.node().getId());
                res.setStatus(204);
            }
            case Missing m -> fail(res, 404);
            default -> fail(res, 403);
        }
    }

    private void mkcol(CurrentUser user, String path, HttpServletRequest req, HttpServletResponse res)
            throws IOException {
        if (req.getContentLengthLong() > 0) {
            fail(res, 415);
            return;
        }
        if (!(resolve(user, path) instanceof Missing)) {
            fail(res, 405, "Already exists");
            return;
        }
        Node parent = resolveParent(user, path);
        if (parent == null) {
            fail(res, 409);
            return;
        }
        if (parent.getKind() == Node.Kind.SITE) {
            content.createLibrary(user, parent.getId(), name(path), null, null);
        } else {
            content.createFolder(user, parent.getId(), name(path));
        }
        res.setStatus(201);
    }

    // ---- MOVE / COPY ---------------------------------------------------------------------------

    private void moveOrCopy(CurrentUser user, String path, HttpServletRequest req, HttpServletResponse res,
                            boolean move) throws IOException {
        String dest = destination(req);
        if (dest == null) {
            fail(res, 400, "Missing or foreign Destination header");
            return;
        }
        boolean overwrite = !"F".equalsIgnoreCase(req.getHeader("Overwrite"));
        Target src = resolve(user, path);
        if (src instanceof Missing) {
            fail(res, 404);
            return;
        }
        if (dest.equalsIgnoreCase(path)) {
            fail(res, 403, "Source and destination are the same");
            return;
        }
        boolean destGhost = GhostStore.isGhostName(name(dest));
        Target existingDest = resolve(user, dest);
        boolean destExists = !(existingDest instanceof Missing);
        if (destExists && !overwrite) {
            fail(res, 412);
            return;
        }

        switch (src) {
            case GhostTarget g -> {
                GhostStore.Ghost ghost = g.ghost();
                if (destGhost) {
                    if (move) ghosts.rename(user.id(), path, dest);
                    else if (ghost.data() != null) ghosts.putData(user.id(), dest, ghost.data());
                } else if (ghost.isAlias()) {
                    // the real document being renamed back (or onward) from its temporary name
                    Node node = content.get(user, ghost.aliasOf());
                    if (!node.getPath().equalsIgnoreCase(dest)) relocate(user, node, dest);
                    if (move) ghosts.remove(user.id(), path);
                } else {
                    // a temp file becoming the document: the "safe save" pattern → new version, same history
                    Node target = destExists && existingDest instanceof Real r ? r.node() : aliasedAwayAt(user, dest);
                    if (target != null && target.getKind() == Node.Kind.FILE) {
                        documents.saveContent(user, target.getId(), null, ghost.data(), null);
                        ghosts.dropAliasesOf(user.id(), target.getId());
                    } else {
                        Node parent = resolveParent(user, dest);
                        if (parent == null) {
                            fail(res, 409);
                            return;
                        }
                        documents.upload(user, parent.getId(), name(dest), null, ghost.data(), false, null);
                    }
                    if (move) ghosts.remove(user.id(), path);
                }
            }
            case Real r -> {
                Node node = r.node();
                if (node.getKind() == Node.Kind.SITE || node.getKind() == Node.Kind.LIBRARY) {
                    fail(res, 403, "Sites and libraries cannot be moved or copied over WebDAV");
                    return;
                }
                if (destGhost) {
                    if (move) {
                        content.get(user, node.getId()); // must still be visible
                        ghosts.putAlias(user.id(), dest, node.getId());
                    } else if (node.getKind() == Node.Kind.FILE) {
                        withContent(user, node, data -> ghosts.putData(user.id(), dest, data));
                    }
                } else {
                    if (destExists && existingDest instanceof Real d) content.delete(user, d.node().getId());
                    if (move) relocate(user, node, dest);
                    else copyTree(user, node, dest);
                }
            }
            default -> {
                fail(res, 403);
                return;
            }
        }
        res.setStatus(destExists ? 204 : 201);
    }

    /** Renames and/or moves a node so that it ends up at {@code dest}. */
    private void relocate(CurrentUser user, Node node, String dest) {
        Node parent = resolveParent(user, dest);
        if (parent == null) throw ApiException.conflict("no_parent", "Destination folder does not exist");
        String newName = name(dest);
        if (!parent.getId().equals(node.getParentId())) content.move(user, node.getId(), parent.getId());
        if (!newName.equals(node.getName())) content.update(user, node.getId(), newName, null, null, null);
    }

    private void copyTree(CurrentUser user, Node src, String dest) throws IOException {
        Node parent = resolveParent(user, dest);
        if (parent == null) throw ApiException.conflict("no_parent", "Destination folder does not exist");
        if (src.getKind() == Node.Kind.FILE) {
            withContent(user, src, data ->
                    documents.upload(user, parent.getId(), name(dest), src.getMimeType(), data, true, "Copied from " + src.getPath()));
            return;
        }
        Node folder = content.createFolder(user, parent.getId(), name(dest));
        for (Node child : content.children(user, src.getId())) {
            copyTree(user, child, folder.getPath() + "/" + child.getName());
        }
    }

    private interface DataConsumer {
        void accept(Path data) throws IOException;
    }

    private void withContent(CurrentUser user, Node file, DataConsumer consumer) throws IOException {
        Path tmp = Files.createTempFile("nexora-dav-copy-", ".bin");
        try {
            DocumentService.Download d = documents.open(user, file.getId(), null);
            try (InputStream in = d.stream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            consumer.accept(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---- LOCK / UNLOCK -------------------------------------------------------------------------

    private void lock(CurrentUser user, String path, HttpServletRequest req, HttpServletResponse res) throws IOException {
        Duration timeout = parseTimeout(req.getHeader("Timeout"));
        String href = PREFIX + encodePath(path);
        byte[] body = readSmall(req);

        // refresh: LOCK with an If header and no body
        if (body.length == 0) {
            String token = firstToken(req.getHeader("If"));
            if (token == null) {
                fail(res, 400, "Lock refresh needs an If header");
                return;
            }
            if (token.startsWith("opaquelocktoken:ghost-")) {
                writeLock(res, 200, token, null, timeout, href);
                return;
            }
            EditLock l = locks.refresh(user, token, timeout);
            writeLock(res, 200, l.getToken(), l.getOwnerInfo(), Duration.between(Instant.now(), l.getExpiresAt()), href);
            return;
        }

        String owner = DavXml.lockOwner(DavXml.parse(body));
        if (GhostStore.isGhostName(name(path))) {
            writeLock(res, 200, "opaquelocktoken:ghost-" + UUID.randomUUID(), owner, timeout, href);
            return;
        }
        Target t = resolve(user, path);
        int status = 200;
        Node file;
        if (t instanceof Missing) {
            // RFC 4918 §9.10.4: locking an unmapped URL creates an empty resource
            Node parent = resolveParent(user, path);
            if (parent == null) {
                fail(res, 409);
                return;
            }
            Path empty = Files.createTempFile("nexora-dav-empty-", ".bin");
            try {
                file = documents.upload(user, parent.getId(), name(path), null, empty, false, null);
            } finally {
                Files.deleteIfExists(empty);
            }
            status = 201;
        } else if (t instanceof Real r && r.node().getKind() == Node.Kind.FILE) {
            file = r.node();
        } else {
            fail(res, 405, "Only files can be locked");
            return;
        }
        EditLock l = locks.acquire(user, file, owner, req.getHeader("User-Agent"), timeout);
        writeLock(res, status, l.getToken(), owner, timeout, href);
    }

    private void unlock(CurrentUser user, String path, HttpServletRequest req, HttpServletResponse res)
            throws IOException {
        String token = firstToken(req.getHeader("Lock-Token"));
        if (token == null) {
            fail(res, 400, "Missing Lock-Token header");
            return;
        }
        if (token.startsWith("opaquelocktoken:ghost-")) {
            res.setStatus(204);
            return;
        }
        Target t = resolve(user, path);
        if (!(t instanceof Real r)) {
            fail(res, 409);
            return;
        }
        locks.release(user, r.node(), token);
        res.setStatus(204);
    }

    private void writeLock(HttpServletResponse res, int status, String token, String owner, Duration timeout,
                           String href) throws IOException {
        byte[] xml = DavXml.lockResponse(token, owner, Math.max(1, timeout.toSeconds()), href)
                .getBytes(StandardCharsets.UTF_8);
        res.setStatus(status);
        res.setHeader("Lock-Token", "<" + token + ">");
        res.setContentType("application/xml; charset=utf-8");
        res.setContentLength(xml.length);
        res.getOutputStream().write(xml);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Node existingFile(CurrentUser user, String path) {
        Target t = resolve(user, path);
        if (t instanceof Real r && r.node().getKind() == Node.Kind.FILE) return r.node();
        return aliasedAwayAt(user, path);
    }

    /** The real document at {@code path} that this user has temporarily renamed away, if any. */
    private Node aliasedAwayAt(CurrentUser user, String path) {
        try {
            Node n = content.resolveDecoded(user, path);
            return n.getKind() == Node.Kind.FILE && ghosts.isAliasedAway(user.id(), n.getId()) ? n : null;
        } catch (ApiException e) {
            return null;
        }
    }

    private Node resolveParent(CurrentUser user, String path) {
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return null;
        Target t = resolve(user, path.substring(0, slash));
        return t instanceof Real r && r.node().getKind().isContainer() ? r.node() : null;
    }

    private static boolean isDavVisible(Node n) {
        return n.getKind() != Node.Kind.LIST && n.getKind() != Node.Kind.LIST_ITEM;
    }

    private String etagOf(Node n) {
        return n.getCurrentVersionId() == null ? n.getId().toString() : n.getCurrentVersionId().toString();
    }

    static String davPath(HttpServletRequest req) {
        String p = req.getPathInfo();
        if (p == null || p.isEmpty()) return "/";
        while (p.contains("//")) p = p.replace("//", "/");
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    /** The Destination header as a decoded DAV path, or null if it points outside /dav. */
    static String destination(HttpServletRequest req) {
        String d = req.getHeader("Destination");
        if (d == null) return null;
        String rawPath;
        try {
            rawPath = d.startsWith("/") ? d : URI.create(d.replace(" ", "%20")).getRawPath();
        } catch (IllegalArgumentException e) {
            return null;
        }
        String decoded = UriUtils.decode(rawPath, StandardCharsets.UTF_8);
        String base = req.getContextPath() + PREFIX;
        if (!decoded.startsWith(base + "/") && !decoded.equals(base)) return null;
        String p = decoded.substring(base.length());
        if (p.isEmpty()) return "/";
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static String hrefFor(String nodePath, boolean collection) {
        return PREFIX + encodePath(nodePath) + (collection ? "/" : "");
    }

    static String encodePath(String path) {
        StringBuilder b = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            b.append('/').append(UriUtils.encodePathSegment(seg, StandardCharsets.UTF_8));
        }
        return b.isEmpty() ? "/" : b.toString();
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String firstToken(String header) {
        if (header == null) return null;
        Matcher m = LOCK_TOKEN.matcher(header);
        return m.find() ? m.group(1) : null;
    }

    static Duration parseTimeout(String header) {
        if (header == null) return EditLockService.DEFAULT_TIMEOUT;
        for (String part : header.split(",")) {
            String p = part.strip();
            if (p.equalsIgnoreCase("Infinite")) return EditLockService.MAX_TIMEOUT;
            if (p.regionMatches(true, 0, "Second-", 0, 7)) {
                try {
                    return Duration.ofSeconds(Long.parseLong(p.substring(7)));
                } catch (NumberFormatException ignored) {
                    // try the next value
                }
            }
        }
        return EditLockService.DEFAULT_TIMEOUT;
    }

    private static byte[] readSmall(HttpServletRequest req) throws IOException {
        try (InputStream in = req.getInputStream()) {
            byte[] data = in.readNBytes(256 * 1024);
            return data;
        }
    }

    private static Path spool(HttpServletRequest req) throws IOException {
        Path tmp = Files.createTempFile("nexora-dav-", ".bin");
        try (InputStream in = req.getInputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp;
    }

    /** Sets an error status with a short text body (no forward to the /error page). */
    private static void fail(HttpServletResponse res, int status) throws IOException {
        fail(res, status, null);
    }

    private static void fail(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        byte[] body = (message == null ? "" : message).getBytes(StandardCharsets.UTF_8);
        res.setContentType("text/plain;charset=utf-8");
        res.setContentLength(body.length);
        if (body.length > 0) res.getOutputStream().write(body);
    }

    private static void multistatus(HttpServletResponse res, String xml) throws IOException {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        res.setStatus(207);
        res.setContentType("application/xml; charset=utf-8");
        res.setContentLength(bytes.length);
        try (OutputStream out = res.getOutputStream()) {
            out.write(bytes);
        }
    }
}
