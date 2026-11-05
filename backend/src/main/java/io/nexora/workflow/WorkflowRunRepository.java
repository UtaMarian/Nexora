package io.nexora.workflow;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {
    List<WorkflowRun> findByRuleIdInOrderByRanAtDesc(Collection<UUID> ruleIds, Pageable page);

    boolean existsByRuleIdAndFireKey(UUID ruleId, String fireKey);
}
