package io.nexora.migration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MigrationSourceRepository extends JpaRepository<MigrationSource, UUID> {
    List<MigrationSource> findAllByOrderByNameAsc();
}
