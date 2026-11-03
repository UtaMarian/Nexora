package io.nexora.pages;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SitePageRepository extends JpaRepository<SitePage, UUID> {
    List<SitePage> findBySiteId(UUID siteId);

    Optional<SitePage> findBySiteIdAndName(UUID siteId, String name);

    boolean existsBySiteIdAndName(UUID siteId, String name);

    @Query("select p from SitePage p where p.siteId = :site and p.kind = :kind and p.publishedVersion > 0 "
            + "order by p.publishedAt desc")
    List<SitePage> findPublished(@Param("site") UUID siteId, @Param("kind") SitePage.Kind kind,
                                 org.springframework.data.domain.Pageable page);
}
