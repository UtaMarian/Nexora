package io.nexora.search;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SearchTextRepository extends JpaRepository<SearchText, String> {

    @Query("select t.status, count(t) from SearchText t group by t.status")
    List<Object[]> countByStatus();

    /** Live documents whose current version could not be read, newest first. */
    @Query("""
            select new io.nexora.search.SearchTextRepository$Unread(n.path, t.status, t.detail, t.extractedAt)
            from SearchText t, FileVersion v, Node n
            where v.storageKey = t.storageKey and n.currentVersionId = v.id and n.deletedAt is null and t.status in :statuses
            order by t.extractedAt desc
            """)
    List<Unread> unread(@Param("statuses") Collection<SearchText.Status> statuses, Pageable page);

    record Unread(String path, SearchText.Status status, String detail, Instant extractedAt) {}

    @Modifying
    @Transactional
    @Query("delete from SearchText t where t.status in :statuses")
    int deleteByStatusIn(@Param("statuses") Collection<SearchText.Status> statuses);

    /** In its own transaction, because the blob collector runs after the caller's has committed. */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("delete from SearchText t where t.storageKey = :key")
    int forget(@Param("key") String storageKey);
}
