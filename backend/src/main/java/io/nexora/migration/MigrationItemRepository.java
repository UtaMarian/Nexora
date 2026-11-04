package io.nexora.migration;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MigrationItemRepository extends JpaRepository<MigrationItem, UUID> {
    Optional<MigrationItem> findByTaskIdAndSourceKey(UUID taskId, String sourceKey);

    List<MigrationItem> findByTaskId(UUID taskId);

    long countByTaskId(UUID taskId);

    @Query("select i from MigrationItem i where lower(i.sourceUrl) = lower(:url)")
    List<MigrationItem> findBySourceUrl(@Param("url") String url);

    @Query("select i from MigrationItem i where lower(i.sourceKey) = lower(:key)")
    List<MigrationItem> findBySourceKey(@Param("key") String key);

    /** Site-level mappings, longest source URL first, for prefix rewrites of old links. */
    @Query("select i from MigrationItem i where i.kind = 'SITE' order by length(i.sourceUrl) desc")
    List<MigrationItem> sites(Pageable page);
}
