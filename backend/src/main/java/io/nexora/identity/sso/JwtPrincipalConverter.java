package io.nexora.identity.sso;

import io.nexora.identity.NexoraUserDetails;
import io.nexora.identity.Principal;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Bearer tokens from Keycloak (scripts, integrations, other services) → NEXORA principal. The user and their
 * directory groups are provisioned exactly as at an interactive sign-in.
 */
public class JwtPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final DirectoryProvisioner provisioner;
    private final IdentityProperties.Oidc cfg;

    public JwtPrincipalConverter(DirectoryProvisioner provisioner, IdentityProperties.Oidc cfg) {
        this.provisioner = provisioner;
        this.cfg = cfg;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Principal p = provisioner.provision(Claims.toUser(jwt.getClaims(), cfg));
        if (!p.isEnabled()) throw new DisabledException("Account disabled");
        return new Token(new NexoraUserDetails(p), jwt);
    }

    static final class Token extends AbstractAuthenticationToken {
        private final NexoraUserDetails principal;
        private final Jwt jwt;

        Token(NexoraUserDetails principal, Jwt jwt) {
            super(principal.getAuthorities());
            this.principal = principal;
            this.jwt = jwt;
            setAuthenticated(true);
        }

        @Override public Object getCredentials() { return jwt; }
        @Override public Object getPrincipal() { return principal; }
        @Override public String getName() { return principal.getLogin(); }
    }
}
