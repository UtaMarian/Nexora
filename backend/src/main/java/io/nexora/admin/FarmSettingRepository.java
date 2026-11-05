package io.nexora.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FarmSettingRepository extends JpaRepository<FarmSetting, String> {
    /** Changes a setting only if it still holds {@code expected}. Of several servers racing, one gets 1. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update FarmSetting s set s.settingValue = :value where s.name = :name and s.settingValue = :expected")
    int compareAndSet(@Param("name") String name, @Param("expected") String expected, @Param("value") String value);
}
