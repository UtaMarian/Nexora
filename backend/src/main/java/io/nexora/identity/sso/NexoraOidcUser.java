package io.nexora.identity.sso;

import io.nexora.identity.NexoraPrincipal;
import io.nexora.identity.Principal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** An OIDC-authenticated user, linked to its NEXORA principal. */
public class NexoraOidcUser implements OidcUser, NexoraPrincipal, Serializable {
    private final OidcUser oidc;
    private final UUID id;
    private final String login;
    private final String displayName;
    private final boolean admin;

    public NexoraOidcUser(OidcUser oidc, Principal p) {
        this.oidc = oidc;
        this.id = p.getId();
        this.login = p.getLogin();
        this.displayName = p.getDisplayName();
        this.admin = p.isAdmin();
    }

    @Override public UUID getId() { return id; }
    @Override public String getLogin() { return login; }
    @Override public String getDisplayName() { return displayName; }
    @Override public boolean isAdmin() { return admin; }

    @Override public Map<String, Object> getClaims() { return oidc.getClaims(); }
    @Override public OidcUserInfo getUserInfo() { return oidc.getUserInfo(); }
    @Override public OidcIdToken getIdToken() { return oidc.getIdToken(); }
    @Override public Map<String, Object> getAttributes() { return oidc.getAttributes(); }
    @Override public String getName() { return login; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return admin
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
