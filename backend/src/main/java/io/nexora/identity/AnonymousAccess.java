package io.nexora.identity;

import io.nexora.admin.FarmSetting;
import io.nexora.admin.FarmSettingRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reading without signing in, as SharePoint's anonymous access works. An administrator allows it for the farm, then site
 * owners open a site, library or list by giving this built-in account Read. Everything else follows from that: the
 * account goes through the same permission checks as anyone, so inheritance, unique permissions and search all apply.
 *
 * <p>Off unless an administrator turned it on. Read from the database on every use, so turning it off takes effect at
 * once on all servers, while the choices owners made are kept. The account cannot sign in.
 */
@Component
public class AnonymousAccess {
    public static final String LOGIN = "nexora-anonymous";
    public static final String NAME = "Anonymous visitors";
    private static final String SETTING = "anonymous.enabled";

    private final FarmSettingRepository settings;
    private final PrincipalRepository principals;
    private volatile CurrentUser visitor;

    public AnonymousAccess(FarmSettingRepository settings, PrincipalRepository principals) {
        this.settings = settings;
        this.principals = principals;
    }

    public boolean enabled() {
        return settings.findById(SETTING).map(s -> Boolean.parseBoolean(s.getValue())).orElse(false);
    }

    public void set(boolean enabled, UUID actor) {
        settings.save(new FarmSetting(SETTING, String.valueOf(enabled), actor));
    }

    public CurrentUser visitor() {
        CurrentUser v = visitor;
        if (v == null) {
            Principal p = principals.findByKindAndLogin(Principal.Kind.USER, LOGIN).orElseGet(() -> {
                Principal created = Principal.user(LOGIN, NAME, null);
                created.setEnabled(false);
                return principals.save(created);
            });
            v = visitor = new CurrentUser(p.getId(), p.getLogin(), p.getDisplayName(), false);
        }
        return v;
    }

    public UUID id() {
        return visitor().id();
    }

    /** A copy without sign-in names, for what visitors receive. Saved person values hold one. */
    public static Object withoutLogins(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (!"login".equals(k)) out.put(k, withoutLogins(v));
            });
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(withoutLogins(v)));
            return out;
        }
        return value;
    }
}
