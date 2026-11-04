package io.nexora.admin;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A setting an administrator changes at run time, as opposed to install-time configuration. */
@Entity
@Table(name = "farm_setting")
public class FarmSetting {
    @Id
    private String name;
    private String settingValue;
    private UUID updatedBy;
    private Instant updatedAt;

    protected FarmSetting() {}

    public FarmSetting(String name, String value, UUID updatedBy) {
        this.name = name;
        this.settingValue = value;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public String getValue() { return settingValue; }
}
