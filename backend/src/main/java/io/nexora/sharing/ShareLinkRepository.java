package io.nexora.sharing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShareLinkRepository extends JpaRepository<ShareLink, UUID> {
    Optional<ShareLink> findByTokenHash(String tokenHash);

    List<ShareLink> findByNodeIdOrderByCreatedAtDesc(UUID nodeId);

    @Query("select l from ShareLink l where l.revokedAt is null and l.expiresAt > :now order by l.createdAt desc")
    List<ShareLink> active(@Param("now") Instant now, Pageable page);
}
