package io.nexora.identity.sso;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Reads a directory identity from OIDC / JWT claims as Keycloak issues them. */
final class Claims {
    private Claims() {}

    static DirectoryProvisioner.DirectoryUser toUser(Map<String, Object> claims, IdentityProperties.Oidc cfg) {
        String sub = str(claims.get("sub"));
        String login = str(claims.get("preferred_username"));
        String name = str(claims.get("name"));
        if (name == null) {
            String given = str(claims.get("given_name"));
            String family = str(claims.get("family_name"));
            name = given == null && family == null ? null : ((given == null ? "" : given) + " " + (family == null ? "" : family)).strip();
        }
        return new DirectoryProvisioner.DirectoryUser(sub, login, name, str(claims.get("email")), true,
                list(claims.get(cfg.groupsClaim())), list(claims.get(cfg.extraGroupsClaim())));
    }

    private static String str(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString();
    }

    /** A missing claim means "member of nothing": the provider is configured to always send it. */
    private static List<String> list(Object o) {
        if (o instanceof Collection<?> c) return c.stream().map(String::valueOf).toList();
        if (o instanceof String s && !s.isBlank()) return List.of(s);
        return List.of();
    }
}
