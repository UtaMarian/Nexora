package io.nexora.audit;

import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Records every read, write, share and permission change. Events are written in the caller's
 * transaction (so a rolled-back change leaves no event) and mirrored to the {@code nexora.audit}
 * logger, which operators can route to syslog / SIEM.
 */
@Service
public class AuditService {
    private static final Logger SIEM = LoggerFactory.getLogger("nexora.audit");

    private final AuditRepository repo;

    public AuditService(AuditRepository repo) {
        this.repo = repo;
    }

    public void record(CurrentUser actor, String action, Node node, Map<String, Object> details) {
        AuditEvent e = new AuditEvent(
                actor == null ? null : actor.id(),
                actor == null ? null : actor.login(),
                action,
                node == null ? null : node.getId(),
                node == null ? null : node.getSiteId(),
                node == null ? null : node.getPath(),
                details == null || details.isEmpty() ? null : details);
        repo.save(e);
        SIEM.info("action={} actor={} node={} path=\"{}\" details={}",
                action, e.getActorLogin(), e.getNodeId(), e.getNodePath(), e.getDetails());
    }

    public void record(CurrentUser actor, String action, Node node) {
        record(actor, action, node, null);
    }
}
