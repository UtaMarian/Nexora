package io.nexora.workflow;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface WorkflowEventRepository extends JpaRepository<WorkflowEvent, Long> {
    @Query("select e from WorkflowEvent e order by e.id")
    List<WorkflowEvent> next(Pageable page);

    boolean existsByNodeIdAndEventType(UUID nodeId, WorkflowEvent.Type eventType);
}
