package io.nexora.content;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EditLockRepository extends JpaRepository<EditLock, UUID> {
    Optional<EditLock> findByNodeId(UUID nodeId);

    Optional<EditLock> findByToken(String token);

    @Query("select l from EditLock l where l.nodeId in :ids and l.expiresAt > :now")
    List<EditLock> findActive(@Param("ids") Collection<UUID> nodeIds, @Param("now") Instant now);

    @Modifying
    @Query("delete from EditLock l where l.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
