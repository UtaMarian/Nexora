package io.nexora.api;

import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.api.Dtos.AuditDto;
import io.nexora.api.Dtos.PageDto;
import io.nexora.audit.AuditEvent;
import io.nexora.audit.AuditRepository;
import io.nexora.common.ApiException;
import io.nexora.content.ContentService;
import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Audit trail. Administrators see everything; site owners (MANAGE_WEB) see their site's events. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
    private final AuditRepository repo;
    private final ContentService content;
    private final PermissionService permissions;

    public AuditController(AuditRepository repo, ContentService content, PermissionService permissions) {
        this.repo = repo;
        this.content = content;
        this.permissions = permissions;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PageDto<AuditDto> search(@RequestParam(required = false) UUID siteId,
                                    @RequestParam(required = false) UUID nodeId,
                                    @RequestParam(required = false) UUID actorId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        CurrentUser user = CurrentUser.get();
        if (!user.admin()) {
            if (siteId == null) throw ApiException.forbidden("Only administrators can view the global audit log");
            Node site = content.get(user, siteId);
            if (site.getKind() != Node.Kind.SITE) throw ApiException.badRequest("not_a_site", "siteId must be a site");
            permissions.require(user, site, Right.MANAGE_WEB);
        }
        Page<AuditEvent> result = repo.search(siteId, nodeId, actorId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500)));
        return new PageDto<>(result.map(e -> new AuditDto(e.getId(), e.getOccurredAt(), e.getActorLogin(),
                e.getAction(), e.getNodeId(), e.getNodePath(), e.getDetails())).getContent(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }
}
