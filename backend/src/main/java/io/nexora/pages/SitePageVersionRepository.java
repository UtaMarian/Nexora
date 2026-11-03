package io.nexora.pages;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SitePageVersionRepository extends JpaRepository<SitePageVersion, UUID> {
    List<SitePageVersion> findByPageIdOrderByVersionDesc(UUID pageId);

    Optional<SitePageVersion> findByPageIdAndVersion(UUID pageId, int version);
}
