package io.nexora.content;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FileVersionRepository extends JpaRepository<FileVersion, UUID> {

    @Query("select v from FileVersion v where v.nodeId = :nodeId order by v.major desc, v.minor desc")
    List<FileVersion> findHistory(@Param("nodeId") UUID nodeId);

    List<FileVersion> findByNodeIdIn(Collection<UUID> nodeIds);

    long countByStorageKey(String storageKey);
}
