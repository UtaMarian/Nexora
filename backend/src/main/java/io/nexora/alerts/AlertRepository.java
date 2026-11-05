package io.nexora.alerts;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<Alert, UUID> {
    List<Alert> findByNodeIdIn(Collection<UUID> nodeIds);

    List<Alert> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Alert> findByUserIdAndNodeId(UUID userId, UUID nodeId);

    List<Alert> findByFrequencyIn(Collection<Alert.Frequency> frequencies);
}
