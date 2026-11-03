package io.nexora.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ColumnDefRepository extends JpaRepository<ColumnDef, UUID> {
    List<ColumnDef> findByLibraryIdOrderByPosition(UUID libraryId);
}
