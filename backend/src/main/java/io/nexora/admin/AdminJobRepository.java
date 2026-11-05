package io.nexora.admin;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AdminJobRepository extends JpaRepository<AdminJob, UUID> {
    @Query("select j from AdminJob j order by j.createdAt desc")
    List<AdminJob> latest(Pageable page);

    List<AdminJob> findByStatusIn(Collection<AdminJob.Status> statuses);

    List<AdminJob> findByTypeAndStatusIn(String type, Collection<AdminJob.Status> statuses);

    java.util.Optional<AdminJob> findFirstByTypeOrderByCreatedAtDesc(String type);
}
