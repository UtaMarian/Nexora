package io.nexora.migration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MigrationTaskRepository extends JpaRepository<MigrationTask, UUID> {
    List<MigrationTask> findAllByOrderByCreatedAtDesc();

    List<MigrationTask> findBySourceId(UUID sourceId);
}
