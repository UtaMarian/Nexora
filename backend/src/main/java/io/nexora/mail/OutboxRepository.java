package io.nexora.mail;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMail, UUID> {

    @Query("select m from OutboxMail m where m.failed = false and m.notBefore <= :now order by m.createdAt")
    List<OutboxMail> due(@Param("now") Instant now, Pageable page);

    long countByFailed(boolean failed);

    List<OutboxMail> findByRecipient(String recipient);
}
