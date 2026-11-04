package io.nexora.search;

import io.nexora.admin.FarmSetting;
import io.nexora.admin.FarmSettingRepository;
import io.nexora.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The administrator's on/off switch for search. Read from the database on every use, so it survives restarts and
 * applies to all servers at once. It is on unless someone turned it off.
 */
@Component
public class SearchSwitch {
    private static final String SETTING = "search.enabled";

    private final FarmSettingRepository settings;

    public SearchSwitch(FarmSettingRepository settings) {
        this.settings = settings;
    }

    public boolean enabled() {
        return settings.findById(SETTING).map(s -> Boolean.parseBoolean(s.getValue())).orElse(true);
    }

    public void set(boolean enabled, UUID actor) {
        settings.save(new FarmSetting(SETTING, String.valueOf(enabled), actor));
    }

    public void requireEnabled() {
        if (!enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "search_disabled", "An administrator has turned search off");
        }
    }
}
