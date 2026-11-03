package io.nexora.identity.sso;

import io.nexora.audit.AuditService;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.Principal;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.Map;

/** After single sign-on, creates/updates the NEXORA user and syncs their directory groups. */
public class NexoraOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
    private final OidcUserService delegate = new OidcUserService();
    private final DirectoryProvisioner provisioner;
    private final IdentityProperties.Oidc cfg;
    private final AuditService audit;

    public NexoraOidcUserService(DirectoryProvisioner provisioner, IdentityProperties.Oidc cfg, AuditService audit) {
        this.provisioner = provisioner;
        this.cfg = cfg;
        this.audit = audit;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser user = delegate.loadUser(request);
        Principal p;
        try {
            p = provisioner.provision(Claims.toUser(user.getClaims(), cfg));
        } catch (DirectoryProvisioner.AccountConflictException e) {
            throw new OAuth2AuthenticationException(new OAuth2Error("account_conflict", e.getMessage(), null));
        }
        if (!p.isEnabled()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("account_disabled", "This account is disabled", null));
        }
        audit.record(CurrentUser.system(p), "LOGIN", null, Map.of("method", "oidc",
                "provider", String.valueOf(user.getClaims().getOrDefault("identity_provider", "keycloak"))));
        return new NexoraOidcUser(user, p);
    }
}
