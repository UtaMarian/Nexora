package io.nexora.lists;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ItemVersionRepository extends JpaRepository<ItemVersion, UUID> {
    List<ItemVersion> findByItemIdOrderByVersionDesc(UUID itemId);

    List<ItemVersion> findByItemIdIn(Collection<UUID> itemIds);

    Optional<ItemVersion> findByItemIdAndVersion(UUID itemId, int version);

    @Query("select coalesce(max(v.version), 0) from ItemVersion v where v.itemId = :itemId")
    int latest(@Param("itemId") UUID itemId);
}
