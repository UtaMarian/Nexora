package io.nexora.search;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SearchQueueRepository extends JpaRepository<SearchQueueEntry, Long> {

    @Query("select e from SearchQueueEntry e where e.kind in :kinds and e.notBefore <= :now order by e.id")
    List<SearchQueueEntry> due(@Param("kinds") Collection<SearchQueueEntry.Kind> kinds, @Param("now") Instant now, Pageable page);

    long countByKindIn(Collection<SearchQueueEntry.Kind> kinds);
}
