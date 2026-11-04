package io.nexora.sharing;

import io.nexora.acl.PermissionLevel;
import io.nexora.alerts.Alert;
import io.nexora.alerts.AlertService;
import io.nexora.api.Dtos.NodeDto;
import io.nexora.api.Dtos.PrincipalRef;
import io.nexora.api.NodeMapper;
import io.nexora.audit.AuditRepository;
import io.nexora.acl.PermissionService;
import io.nexora.common.ApiException;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import io.nexora.identity.CurrentUser;
import io.nexora.mail.MailService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Links, shares, alerts, and the "Shared with me" and "Recent" lists. {@code /api/v1/links/**} needs no sign-in. */
@RestController
@RequestMapping("/api/v1")
public class SharingController {
    private static final List<String> RECENT_ACTIONS = List.of("FILE_VIEW", "FILE_DOWNLOAD", "OFFICE_OPEN", "FILE_UPLOAD", "FILE_UPDATE",
            "CHECK_IN", "METADATA_UPDATE");

    private final SharingService sharing;
    private final LinkAccessService access;
    private final AlertService alerts;
    private final MailService mail;
    private final NodeMapper mapper;
    private final NodeRepository nodes;
    private final PermissionService permissions;
    private final AuditRepository auditEvents;
    private final io.nexora.audit.AuditService audit;

    public SharingController(SharingService sharing, LinkAccessService access, AlertService alerts, MailService mail, NodeMapper mapper,
                             NodeRepository nodes, PermissionService permissions, AuditRepository auditEvents,
                             io.nexora.audit.AuditService audit) {
        this.sharing = sharing;
        this.access = access;
        this.alerts = alerts;
        this.mail = mail;
        this.mapper = mapper;
        this.nodes = nodes;
        this.permissions = permissions;
        this.auditEvents = auditEvents;
        this.audit = audit;
    }

    public record CreateLink(ShareLink.Scope scope, int days, String password) {}

    public record LinkDto(UUID id, ShareLink.Scope scope, Instant expiresAt, boolean hasPassword, int useCount, Instant lastUsedAt,
                          PrincipalRef createdBy, Instant createdAt, String path) {}

    public record NewLink(LinkDto link, String token) {}

    public record ShareRequest(List<UUID> principals, PermissionLevel level, String message) {}

    public record GrantDto(UUID id, PrincipalRef principal, PermissionLevel level, PrincipalRef sharedBy, Instant sharedAt) {}

    public record SharesDto(List<LinkDto> links, List<GrantDto> people, boolean anyoneLinksAllowed, int maxLinkDays, boolean emailEnabled) {}

    public record SharedItem(NodeDto item, PrincipalRef sharedBy, Instant sharedAt, PermissionLevel level) {}

    public record RecentItem(NodeDto item, Instant at) {}

    public record AlertRequest(Alert.Frequency frequency) {}

    public record SharingPolicy(boolean anyoneLinksAllowed, int maxLinkDays, MailService.Status mail, String mailProblem, List<LinkDto> links) {}

    public record PolicyRequest(boolean anyoneLinksAllowed) {}

    public record TestMail(String to) {}

    private LinkDto dto(ShareLink l, String path) {
        return new LinkDto(l.getId(), l.getScope(), l.getExpiresAt(), l.getPasswordHash() != null, l.getUseCount(), l.getLastUsedAt(),
                mapper.ref(l.getCreatedBy()), l.getCreatedAt(), path);
    }

    // ---- sharing an item ---------------------------------------------------------------------------

    @GetMapping("/nodes/{id}/shares")
    public SharesDto shares(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        List<LinkDto> links = sharing.links(user, id).stream().map(l -> dto(l, null)).toList();
        List<GrantDto> people = sharing.grants(user, id).stream()
                .map(g -> new GrantDto(g.getId(), mapper.ref(g.getPrincipalId()), g.getLevel(), mapper.ref(g.getSharedBy()), g.getSharedAt())).toList();
        return new SharesDto(links, people, sharing.anyoneLinksAllowed(), sharing.maxLinkDays(), mail.enabled());
    }

    @PostMapping("/nodes/{id}/share-links")
    @ResponseStatus(HttpStatus.CREATED)
    public NewLink createLink(@PathVariable UUID id, @RequestBody CreateLink body) {
        SharingService.CreatedLink created = sharing.createLink(CurrentUser.get(), id, body.scope(), body.days(), body.password());
        return new NewLink(dto(created.link(), null), created.token());
    }

    @DeleteMapping("/share-links/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeLink(@PathVariable UUID id) {
        sharing.revokeLink(CurrentUser.get(), id);
    }

    @PostMapping("/nodes/{id}/share-people")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void share(@PathVariable UUID id, @RequestBody ShareRequest body) {
        sharing.share(CurrentUser.get(), id, body.principals(), body.level(), body.message());
    }

    @DeleteMapping("/share-grants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unshare(@PathVariable UUID id) {
        sharing.unshare(CurrentUser.get(), id);
    }

    // ---- my lists ----------------------------------------------------------------------------------

    @GetMapping("/me/shared-with-me")
    public List<SharedItem> sharedWithMe() {
        CurrentUser user = CurrentUser.get();
        var shared = sharing.sharedWith(user);
        Map<UUID, Node> found = live(user, shared.stream().map(ShareGrant::getNodeId).toList());
        Map<UUID, SharedItem> out = new LinkedHashMap<>();
        for (ShareGrant g : shared) {
            Node n = found.get(g.getNodeId());
            if (n != null) out.putIfAbsent(n.getId(), new SharedItem(mapper.one(user, n), mapper.ref(g.getSharedBy()), g.getSharedAt(), g.getLevel()));
        }
        return new ArrayList<>(out.values());
    }

    @GetMapping("/me/recent")
    public List<RecentItem> recent() {
        CurrentUser user = CurrentUser.get();
        List<Object[]> rows = auditEvents.recentNodes(user.id(), RECENT_ACTIONS, PageRequest.of(0, 60));
        Map<UUID, Node> found = live(user, rows.stream().map(r -> (UUID) r[0]).toList());
        List<RecentItem> out = new ArrayList<>();
        for (Object[] r : rows) {
            Node n = found.get((UUID) r[0]);
            if (n != null && n.getKind() == Node.Kind.FILE && out.size() < 30) out.add(new RecentItem(mapper.one(user, n), (Instant) r[1]));
        }
        return out;
    }

    /** Puts a file in "Recent" when someone opens it in the browser. */
    @PostMapping("/nodes/{id}/viewed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void viewed(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        Node node = nodes.findLive(id).orElseThrow(() -> ApiException.notFound("Item"));
        permissions.require(user, node, io.nexora.acl.Right.VIEW);
        audit.record(user, "FILE_VIEW", node);
    }

    private Map<UUID, Node> live(CurrentUser user, List<UUID> ids) {
        List<Node> found = nodes.findAllById(new HashSet<>(ids)).stream().filter(n -> !n.isDeleted()).toList();
        return permissions.filterVisible(user, found).stream().collect(Collectors.toMap(Node::getId, Function.identity()));
    }

    // ---- alerts ------------------------------------------------------------------------------------

    @GetMapping("/me/alerts")
    public List<AlertService.AlertDto> myAlerts() {
        return alerts.mine(CurrentUser.get());
    }

    @GetMapping("/nodes/{id}/alert")
    public ResponseEntity<AlertService.AlertDto> alert(@PathVariable UUID id) {
        return alerts.find(CurrentUser.get(), id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/nodes/{id}/alert")
    public AlertService.AlertDto subscribe(@PathVariable UUID id, @RequestBody AlertRequest body) {
        return alerts.subscribe(CurrentUser.get(), id, body.frequency());
    }

    @DeleteMapping("/me/alerts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@PathVariable UUID id) {
        alerts.unsubscribe(CurrentUser.get(), id);
    }

    // ---- links, no sign-in needed -------------------------------------------------------------------

    @GetMapping("/links/{token}")
    public LinkAccessService.Opened open(@PathVariable String token, @RequestParam(required = false) UUID folder,
                                         @RequestHeader(value = "X-Link-Password", required = false) String password,
                                         HttpServletResponse response) {
        response.setHeader("Referrer-Policy", "no-referrer");
        return access.open(token, password, folder, signedIn());
    }

    @GetMapping("/links/{token}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable String token, @RequestParam(required = false) UUID file,
                                                       @RequestHeader(value = "X-Link-Password", required = false) String password) {
        LinkAccessService.Content c = access.download(token, password, file, currentOrNull(), signedIn());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(c.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(c.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .header("Referrer-Policy", "no-referrer")
                .body(new InputStreamResource(c.stream()));
    }

    private static boolean signedIn() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    private static CurrentUser currentOrNull() {
        return signedIn() ? CurrentUser.get() : null;
    }

    // ---- administration ----------------------------------------------------------------------------

    @GetMapping("/admin/sharing")
    public SharingPolicy policy() {
        List<LinkDto> active = sharing.activeLinks(200).stream()
                .map(l -> dto(l, nodes.findById(l.getNodeId()).map(Node::getPath).orElse(null))).toList();
        return new SharingPolicy(sharing.anyoneLinksAllowed(), sharing.maxLinkDays(), mail.status(), mail.enabled() ? mail.check() : null, active);
    }

    @PutMapping("/admin/sharing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPolicy(@RequestBody PolicyRequest body) {
        sharing.setAnyoneLinksAllowed(CurrentUser.get(), body.anyoneLinksAllowed());
    }

    @DeleteMapping("/admin/sharing/links/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void adminRevoke(@PathVariable UUID id) {
        sharing.revokeAsAdmin(CurrentUser.get(), id);
    }

    @PostMapping("/admin/sharing/test-mail")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void testMail(@RequestBody TestMail body) {
        if (!mail.enabled()) throw new ApiException(HttpStatus.CONFLICT, "mail_disabled", "Email is turned off on this server");
        mail.queue(body.to(), "[NEXORA] Test message", List.of("It works", "NEXORA can send email through your mail server."), null, null);
    }
}
