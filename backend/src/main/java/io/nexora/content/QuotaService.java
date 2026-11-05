package io.nexora.content;

import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.GroupMember;
import io.nexora.identity.GroupMemberRepository;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.mail.MailService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Storage limits on top-level sites, as with SharePoint's site collection quotas.
 *
 * <p>Usage covers the site, its subsites, every version, private drafts and the recycle bin. Identical content counts
 * once within a site, so publishing a draft, which reuses its bytes, costs nothing. Sites with no limit pay for none
 * of this: the check stops at the lookup of the limit.
 */
@Service
@Transactional
public class QuotaService {
    public record Usage(UUID siteId, long maxBytes, Long warnBytes, long usedBytes) {}

    private final SiteQuotaRepository quotas;
    private final NodeRepository nodes;
    private final NamedParameterJdbcTemplate jdbc;
    private final PrincipalRepository principals;
    private final GroupMemberRepository members;
    private final MailService mail;
    private final AuditService audit;

    public QuotaService(SiteQuotaRepository quotas, NodeRepository nodes, NamedParameterJdbcTemplate jdbc,
                        PrincipalRepository principals, GroupMemberRepository members, MailService mail, AuditService audit) {
        this.quotas = quotas;
        this.nodes = nodes;
        this.jdbc = jdbc;
        this.principals = principals;
        this.members = members;
        this.mail = mail;
        this.audit = audit;
    }

    /** Sets a site's limit. {@code warnBytes} is optional. */
    public Usage set(CurrentUser actor, UUID siteId, long maxBytes, Long warnBytes) {
        Node site = rootSite(siteId);
        if (maxBytes <= 0) throw ApiException.badRequest("invalid_quota", "The limit must be more than zero");
        if (warnBytes != null && (warnBytes <= 0 || warnBytes >= maxBytes)) {
            throw ApiException.badRequest("invalid_quota", "The warning level must be below the limit");
        }
        SiteQuota quota = quotas.findById(site.getId()).orElseGet(() -> new SiteQuota(site.getId()));
        quota.set(maxBytes, warnBytes, actor.id());
        quotas.save(quota);
        audit.record(actor, "SITE_QUOTA_SET", site, Map.of("maxBytes", maxBytes));
        return new Usage(site.getId(), maxBytes, warnBytes, used(site.getId()));
    }

    public void remove(CurrentUser actor, UUID siteId) {
        Node site = rootSite(siteId);
        if (quotas.existsById(site.getId())) {
            quotas.deleteById(site.getId());
            audit.record(actor, "SITE_QUOTA_REMOVE", site);
        }
    }

    /** Limits and current usage by site, for Central Administration. */
    @Transactional(readOnly = true)
    public Map<UUID, Usage> all() {
        Map<UUID, Usage> out = new HashMap<>();
        for (SiteQuota q : quotas.findAll()) {
            out.put(q.getSiteId(), new Usage(q.getSiteId(), q.getMaxBytes(), q.getWarnBytes(), used(q.getSiteId())));
        }
        return out;
    }

    /**
     * Refuses new content that would take a site over its limit, and warns the owners at the warning level. Call it
     * before storing the bytes. Empty files always pass, because Explorer creates a file empty before uploading it.
     */
    public void admit(Node inSite, long newBytes) {
        SiteQuota quota = quotaFor(inSite);
        if (quota == null) return;
        long after = used(quota.getSiteId()) + Math.max(0, newBytes);
        if (newBytes > 0 && after > quota.getMaxBytes()) throw full(quota);
        warnOwners(quota, after);
    }

    /** Refuses a move into another site that does not have room for what is moving. */
    public void admitMove(Node moving, Node target) {
        SiteQuota quota = quotaFor(target);
        if (quota == null || quota.getSiteId().equals(rootOf(moving).map(Node::getId).orElse(null))) return;
        long bytes = sum("(n.id = :id or n.path like :below escape '\\')", new MapSqlParameterSource("id", moving.getId())
                .addValue("below", NodeRepository.likePrefix(moving.getPath())));
        if (used(quota.getSiteId()) + bytes > quota.getMaxBytes()) throw full(quota);
    }

    private ApiException full(SiteQuota quota) {
        String title = nodes.findById(quota.getSiteId()).map(Node::getTitle).orElse("This site");
        return new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "quota_exceeded", "'" + title + "' has reached its storage "
                + "limit. Delete files and empty the recycle bin, or ask an administrator for a higher limit.");
    }

    private void warnOwners(SiteQuota quota, long used) {
        if (quota.getWarnBytes() == null) return;
        boolean over = used >= quota.getWarnBytes();
        if (over == (quota.getWarnedAt() != null)) return;
        quota.setWarnedAt(over ? Instant.now() : null); // falling back below re-arms the warning
        if (!over) return;
        Node site = nodes.findById(quota.getSiteId()).orElse(null);
        if (site == null) return;
        String heading = site.getTitle() + " is using " + used * 100 / quota.getMaxBytes() + "% of its storage";
        for (Principal owner : owners(site)) {
            mail.queue(owner.getEmail(), "[NEXORA] " + heading, List.of(heading,
                    "New files will be refused when the site reaches its limit. Items in the recycle bin count until it is emptied."),
                    "Open the site", mail.link(site.getPath()));
        }
    }

    private List<Principal> owners(Node site) {
        return principals.findByKindAndLogin(Principal.Kind.GROUP, site.groupPrefix() + "-owners")
                .map(g -> principals.findAllById(members.findByGroupId(g.getId()).stream().map(GroupMember::getMemberId).toList()))
                .orElse(List.of());
    }

    private SiteQuota quotaFor(Node node) {
        return rootOf(node).flatMap(root -> quotas.findById(root.getId())).orElse(null);
    }

    /** The top-level site a node belongs to. Its path always starts with {@code /sites/<name>}. */
    private Optional<Node> rootOf(Node node) {
        String[] parts = node.getPath().split("/", 4);
        return parts.length < 3 ? Optional.empty() : nodes.findLiveByPath("/sites/" + parts[2]);
    }

    private Node rootSite(UUID id) {
        Node site = nodes.findLive(id).filter(n -> n.getKind() == Node.Kind.SITE)
                .orElseThrow(() -> ApiException.notFound("Site"));
        if (site.getParentId() != null) {
            throw ApiException.badRequest("subsite_quota", "Set the limit on the top-level site. Subsites count towards it");
        }
        return site;
    }

    private long used(UUID rootId) {
        Set<UUID> sites = new HashSet<>(Set.of(rootId));
        Set<UUID> level = Set.of(rootId);
        while (!level.isEmpty()) { // walks the site tree through the parent index, so usage needs no scan of all paths
            level = new HashSet<>(nodes.findSubsiteIds(level));
            level.removeAll(sites);
            sites.addAll(level);
        }
        return sum("n.site_id in (:sites)", new MapSqlParameterSource("sites", sites));
    }

    /** Bytes of the distinct content matching the condition. Soft-deleted nodes keep their rows, so the bin counts. */
    private long sum(String where, MapSqlParameterSource params) {
        String sql = "select coalesce(sum(size_bytes), 0) from ("
                + "select v.storage_key as storage_key, v.size_bytes as size_bytes from file_version v"
                + " join node n on n.id = v.node_id where " + where
                + " union select n.working_storage_key, n.working_size_bytes from node n"
                + " where n.working_storage_key is not null and " + where + ") content";
        Long total = jdbc.queryForObject(sql, params, Long.class);
        return total == null ? 0 : total;
    }
}
