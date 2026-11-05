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

    /** Each file's latest major version. Files that were never published are absent. */
    @Query("""
            select v from FileVersion v where v.nodeId in :nodeIds and v.minor = 0
              and v.major = (select max(m.major) from FileVersion m where m.nodeId = v.nodeId and m.minor = 0)
            """)
    List<FileVersion> findLatestMajors(@Param("nodeIds") Collection<java.util.UUID> nodeIds);
}
