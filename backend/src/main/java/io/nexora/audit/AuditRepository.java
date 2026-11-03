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
}
