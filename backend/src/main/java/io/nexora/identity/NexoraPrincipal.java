package io.nexora.identity;

import java.util.UUID;

/**
 * The signed-in NEXORA user behind any authentication method: local password, OIDC single sign-on,
 * directory password (WebDAV/Basic) or bearer token.
 */
public interface NexoraPrincipal {
    UUID getId();
    String getLogin();
    String getDisplayName();
    boolean isAdmin();
}
