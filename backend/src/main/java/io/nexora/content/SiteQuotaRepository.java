package io.nexora.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SiteQuotaRepository extends JpaRepository<SiteQuota, UUID> {
}
