package io.nexora.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface AuditRepository extends JpaRepository<AuditEvent, UUID> {

    @Query("""
            select e from AuditEvent e
            where (:siteId is null or e.siteId = :siteId)
              and (:nodeId is null or e.nodeId = :nodeId)
              and (:actorId is null or e.actorId = :actorId)
            order by e.occurredAt desc
            """)
    Page<AuditEvent> search(@Param("siteId") UUID siteId, @Param("nodeId") UUID nodeId,
                            @Param("actorId") UUID actorId, Pageable page);

    /** The items someone touched most recently: node id and when. */
    @Query("""
            select e.nodeId, max(e.occurredAt) from AuditEvent e
            where e.actorId = :actorId and e.action in :actions and e.nodeId is not null
            group by e.nodeId order by max(e.occurredAt) desc
            """)
    java.util.List<Object[]> recentNodes(@Param("actorId") UUID actorId, @Param("actions") java.util.Collection<String> actions, Pageable page);
}
