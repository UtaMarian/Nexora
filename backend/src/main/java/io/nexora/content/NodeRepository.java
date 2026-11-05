package io.nexora.content;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NodeRepository extends JpaRepository<Node, UUID> {

    @Query("select n from Node n where n.id = :id and n.deletedAt is null")
    Optional<Node> findLive(@Param("id") UUID id);

    @Query("select n from Node n where lower(n.path) = lower(:path) and n.deletedAt is null")
    Optional<Node> findLiveByPath(@Param("path") String path);

    @Query("""
            select n from Node n where n.parentId = :parentId and n.deletedAt is null
            order by case when n.kind = io.nexora.content.Node.Kind.FILE then 1 else 0 end, lower(n.name)
            """)
    List<Node> findLiveChildren(@Param("parentId") UUID parentId);

    @Query("""
            select count(n) > 0 from Node n
            where n.parentId = :parentId and lower(n.name) = lower(:name) and n.deletedAt is null
            """)
    boolean existsLiveSibling(@Param("parentId") UUID parentId, @Param("name") String name);

    @Query("select n from Node n where n.kind = io.nexora.content.Node.Kind.SITE and n.deletedAt is null order by lower(n.title)")
    List<Node> findLiveSites();

    @Query("select n from Node n where n.kind = io.nexora.content.Node.Kind.SITE and n.parentId is null and n.deletedAt is null order by lower(n.title)")
    List<Node> findLiveRootSites();

    /** Includes sites in the recycle bin, whose content still takes up space. */
    @Query("select n.id from Node n where n.kind = io.nexora.content.Node.Kind.SITE and n.parentId in :parents")
    List<UUID> findSubsiteIds(@Param("parents") java.util.Collection<UUID> parents);

    @Query("select count(n) > 0 from Node n where n.kind = io.nexora.content.Node.Kind.SITE and n.parentId is null and lower(n.name) = lower(:name)")
    boolean existsSiteName(@Param("name") String name);

    /** All live descendants (not the node itself), by path prefix. */
    @Query("select n from Node n where n.path like :prefix escape '\\' and n.deletedAt is null")
    List<Node> findLiveDescendants(@Param("prefix") String escapedPrefixWithWildcard);

    @Modifying
    @Query("""
            update Node n set n.path = concat(:newPrefix, substring(n.path, :oldLength + 1)), n.siteId = :siteId
            where n.path like :likePrefix escape '\\' and n.deletedAt is null
            """)
    int rewriteDescendantPaths(@Param("likePrefix") String likePrefix,
                               @Param("oldLength") int oldLength,
                               @Param("newPrefix") String newPrefix,
                               @Param("siteId") UUID siteId);

    @Modifying
    @Query("""
            update Node n set n.deletedAt = :at, n.deletedBy = :by, n.deletedRootId = :rootId
            where (n.id = :rootId or n.path like :likePrefix escape '\\') and n.deletedAt is null
            """)
    int softDeleteSubtree(@Param("rootId") UUID rootId, @Param("likePrefix") String likePrefix,
                          @Param("at") Instant at, @Param("by") UUID by);

    @Modifying
    @Query("update Node n set n.deletedAt = null, n.deletedBy = null, n.deletedRootId = null where n.deletedRootId = :rootId")
    int restoreSubtree(@Param("rootId") UUID rootId);

    List<Node> findByDeletedRootId(UUID rootId);

    List<Node> findByParentIdIn(java.util.Collection<UUID> parentIds);

    long countByWorkingStorageKey(String workingStorageKey);

    /** Live descendants checked out by someone other than {@code userId} (blocks delete/move of folders). */
    @Query("""
            select count(n) from Node n
            where n.path like :likePrefix escape '\\' and n.deletedAt is null
              and n.checkedOutBy is not null and n.checkedOutBy <> :userId
            """)
    long countDescendantsCheckedOutByOthers(@Param("likePrefix") String likePrefix, @Param("userId") UUID userId);

    /** Recycle bin: the nodes users explicitly deleted (not the descendants that went with them). */
    @Query("""
            select n from Node n where n.deletedAt is not null and n.deletedRootId = n.id
              and (:siteId is null or n.siteId = :siteId or n.parentId = :siteId)
            order by n.deletedAt desc
            """)
    List<Node> findRecycleBin(@Param("siteId") UUID siteId);

    @Query("""
            select n from Node n
            where n.deletedAt is null and n.kind <> io.nexora.content.Node.Kind.SITE
              and (lower(n.name) like lower(concat('%', :q, '%')) or lower(n.title) like lower(concat('%', :q, '%')))
            order by n.modifiedAt desc
            """)
    List<Node> searchByName(@Param("q") String q, Pageable page);

    static String likePrefix(String path) {
        return path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%";
    }
}
