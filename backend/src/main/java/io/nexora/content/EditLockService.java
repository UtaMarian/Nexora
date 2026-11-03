package io.nexora.content;

import io.nexora.acl.PermissionService;
import io.nexora.acl.Right;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.identity.CurrentUser;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Exclusive, expiring edit locks (WebDAV LOCK). One active lock per file. */
@Service
@Transactional
public class EditLockService {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofHours(1);
    public static final Duration MAX_TIMEOUT = Duration.ofHours(24);

    private final EditLockRepository locks;
    private final PermissionService permissions;
    private final AuditService audit;

    public EditLockService(EditLockRepository locks, PermissionService permissions, AuditService audit) {
        this.locks = locks;
        this.permissions = permissions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Optional<EditLock> active(Node node) {
        return locks.findByNodeId(node.getId()).filter(l -> !l.isExpired());
    }

    @Transactional(readOnly = true)
    public Map<UUID, EditLock> activeFor(Collection<UUID> nodeIds) {
        Map<UUID, EditLock> out = new HashMap<>();
        if (nodeIds.isEmpty()) return out;
        locks.findActive(nodeIds, Instant.now()).forEach(l -> out.put(l.getNodeId(), l));
        return out;
    }

    /** True if someone other than {@code user} holds an active lock on the node. */
    @Transactional(readOnly = true)
    public boolean lockedByOther(CurrentUser user, Node node) {
        return active(node).map(l -> !l.getOwnerId().equals(user.id())).orElse(false);
    }

    public EditLock acquire(CurrentUser user, Node file, String ownerInfo, String client, Duration timeout) {
        permissions.require(user, file, Right.EDIT_ITEMS);
        if (file.isCheckedOut() && !file.getCheckedOutBy().equals(user.id())) {
            throw ApiException.locked("'" + file.getName() + "' is checked out by someone else");
        }
        Optional<EditLock> existing = locks.findByNodeId(file.getId());
        if (existing.isPresent()) {
            EditLock l = existing.get();
            if (!l.isExpired() && !l.getOwnerId().equals(user.id())) {
                throw ApiException.locked("'" + file.getName() + "' is open for editing by someone else");
            }
            locks.delete(l);
            locks.flush();
        }
        EditLock lock = locks.save(new EditLock(file.getId(), user.id(), ownerInfo, client,
                Instant.now().plus(clamp(timeout))));
        audit.record(user, "LOCK", file, Map.of("client", client == null ? "" : client));
        return lock;
    }

    public EditLock refresh(CurrentUser user, String token, Duration timeout) {
        EditLock l = locks.findByToken(token).filter(x -> !x.isExpired())
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.PRECONDITION_FAILED,
                        "lock_not_found", "No such lock"));
        if (!l.getOwnerId().equals(user.id())) throw ApiException.forbidden("Not your lock");
        l.setExpiresAt(Instant.now().plus(clamp(timeout)));
        return l;
    }

    /** Releases a lock. The owner, or someone allowed to override check-outs, may release it. */
    public void release(CurrentUser user, Node file, String token) {
        EditLock l = locks.findByNodeId(file.getId())
                .filter(x -> token == null || x.getToken().equals(token))
                .orElseThrow(() -> ApiException.conflict("lock_not_found", "No such lock"));
        if (!l.getOwnerId().equals(user.id())) permissions.require(user, file, Right.OVERRIDE_CHECKOUT);
        locks.delete(l);
        audit.record(user, "UNLOCK", file);
    }

    @Scheduled(fixedDelay = 300_000)
    public void purgeExpired() {
        locks.deleteExpired(Instant.now());
    }

    private static Duration clamp(Duration d) {
        if (d == null || d.isNegative() || d.isZero()) return DEFAULT_TIMEOUT;
        return d.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : d;
    }
}
