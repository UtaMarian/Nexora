package io.nexora.alerts;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {
    @Query("select e from AlertEvent e where e.occurredAt <= :before order by e.id")
    List<AlertEvent> due(@Param("before") Instant before, Pageable page);
}
