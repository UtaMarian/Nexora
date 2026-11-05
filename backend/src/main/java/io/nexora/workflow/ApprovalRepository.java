package io.nexora.workflow;

import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ApprovalRepository extends JpaRepository<Approval, UUID> {
    List<Approval> findByNodeIdOrderByCreatedAtDesc(UUID nodeId);

    /**
     * Holds the row for the rest of the transaction, so changes to one approval happen one at a time. Each answer then
     * sees the others, and the last one finishes it. This must be the first time the transaction loads the approval.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Approval a where a.id = :id")
    java.util.Optional<Approval> lockedById(@Param("id") UUID id);

    List<Approval> findByNodeIdIn(Collection<UUID> nodeIds);

    List<Approval> findByNodeIdAndStatus(UUID nodeId, Approval.Status status);

    List<Approval> findByRequestedByOrderByCreatedAtDesc(UUID requestedBy, Pageable page);

    boolean existsByNodeIdAndStatus(UUID nodeId, Approval.Status status);

    @Query("""
            select distinct a from Approval a, ApprovalStep s
            where s.approvalId = a.id and a.status = io.nexora.workflow.Approval.Status.PENDING
              and s.decision = io.nexora.workflow.ApprovalStep.Decision.PENDING and s.principalId in :principals
            order by a.createdAt desc
            """)
    List<Approval> waitingFor(@Param("principals") Collection<UUID> principals);

    @Query("select a from Approval a where a.status = io.nexora.workflow.Approval.Status.PENDING and a.dueAt < :now and (a.remindedAt is null or a.remindedAt < :since)")
    List<Approval> overdue(@Param("now") Instant now, @Param("since") Instant since);
}
