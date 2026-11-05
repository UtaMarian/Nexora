package io.nexora.sharing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShareGrantRepository extends JpaRepository<ShareGrant, UUID> {
    List<ShareGrant> findByNodeIdOrderBySharedAtDesc(UUID nodeId);

    Optional<ShareGrant> findByNodeIdAndPrincipalId(UUID nodeId, UUID principalId);

    @Query("select g from ShareGrant g where g.principalId in :principals order by g.sharedAt desc")
    List<ShareGrant> sharedWith(@Param("principals") Collection<UUID> principals, Pageable page);
}
