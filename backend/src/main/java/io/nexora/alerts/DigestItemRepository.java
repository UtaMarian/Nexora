package io.nexora.alerts;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DigestItemRepository extends JpaRepository<DigestItem, Long> {
    List<DigestItem> findByAlertIdOrderByOccurredAt(UUID alertId);
}
