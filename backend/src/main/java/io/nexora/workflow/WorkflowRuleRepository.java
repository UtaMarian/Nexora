package io.nexora.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowRuleRepository extends JpaRepository<WorkflowRule, UUID> {
    List<WorkflowRule> findByContainerIdOrderByCreatedAt(UUID containerId);

    List<WorkflowRule> findByEnabledTrue();

    boolean existsByEnabledTrue();
}
