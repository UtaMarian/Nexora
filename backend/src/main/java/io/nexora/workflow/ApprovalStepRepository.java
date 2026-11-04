package io.nexora.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ApprovalStepRepository extends JpaRepository<ApprovalStep, UUID> {
    List<ApprovalStep> findByApprovalIdOrderByPosition(UUID approvalId);

    List<ApprovalStep> findByApprovalIdIn(Collection<UUID> approvalIds);
}
