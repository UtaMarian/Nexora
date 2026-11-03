package io.nexora.acl;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AclEntryRepository extends JpaRepository<AclEntry, UUID> {
    List<AclEntry> findByNodeId(UUID nodeId);

    List<AclEntry> findByNodeIdAndPrincipalIdIn(UUID nodeId, Collection<UUID> principalIds);

    Optional<AclEntry> findByNodeIdAndPrincipalId(UUID nodeId, UUID principalId);

    @Modifying
    @Query("delete from AclEntry e where e.nodeId = :nodeId")
    void deleteByNodeId(@Param("nodeId") UUID nodeId);
}
