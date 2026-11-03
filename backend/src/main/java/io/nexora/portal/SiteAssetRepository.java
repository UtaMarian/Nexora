package io.nexora.portal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SiteAssetRepository extends JpaRepository<SiteAsset, UUID> {
    Optional<SiteAsset> findBySiteIdAndKind(UUID siteId, String kind);
}
