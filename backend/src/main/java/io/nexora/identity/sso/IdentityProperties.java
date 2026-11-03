package io.nexora.identity.sso;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Sign-in configuration. NEXORA trusts one OpenID Connect provider (Keycloak), which in turn federates
 * Active Directory / LDAP (users, groups, Kerberos) and brokers ADFS or other SAML/OIDC providers.
 *
 * @param localLogin allow local accounts (bootstrap admin, break-glass); directory users never have local passwords
 */
@ConfigurationProperties(prefix = "nexora.identity")
public record IdentityProperties(boolean localLogin, Oidc oidc, Sync sync) {

    public IdentityProperties {
        if (oidc == null) oidc = new Oidc(false, null, null, null, null, null, null, null, null, null, List.of(), List.of(), true);
        if (sync == null) sync = new Sync(false, null, null, null);
    }

    /**
     * @param publicUrl   Keycloak base URL as browsers reach it, e.g. https://nexora.example.com/auth
     * @param internalUrl Keycloak base URL as the backend reaches it, e.g. http://keycloak:8080/auth
     * @param groupsClaim claim with the user's groups (Keycloak group paths, e.g. "/Engineering/Backend")
     * @param extraGroupsClaim claim with group names asserted by a brokered provider such as ADFS
     * @param adminGroups groups (path or name) whose members become NEXORA administrators
     * @param identityProviders brokered providers offered as buttons on the sign-in page (Keycloak aliases)
     * @param directoryBasicAuth let directory users authenticate WebDAV/scripts with HTTP Basic (validated by Keycloak)
     */
    public record Oidc(boolean enabled, String displayName, String publicUrl, String internalUrl, String realm,
                       String clientId, String clientSecret, String groupsClaim, String extraGroupsClaim,
                       String postLogoutRedirectUri, List<String> adminGroups, List<IdentityProvider> identityProviders,
                       boolean directoryBasicAuth) {

        public Oidc {
            if (displayName == null || displayName.isBlank()) displayName = "Organization account";
            if (realm == null || realm.isBlank()) realm = "nexora";
            if (clientId == null || clientId.isBlank()) clientId = "nexora";
            if (groupsClaim == null || groupsClaim.isBlank()) groupsClaim = "groups";
            if (extraGroupsClaim == null || extraGroupsClaim.isBlank()) extraGroupsClaim = "directory_groups";
            if (adminGroups == null) adminGroups = List.of();
            identityProviders = identityProviders == null ? List.of() : identityProviders.stream()
                    .filter(p -> p.alias() != null && !p.alias().isBlank())
                    .map(p -> new IdentityProvider(p.alias().strip(),
                            p.label() == null || p.label().isBlank() ? "Sign in with " + p.alias() : p.label()))
                    .toList();
            adminGroups = adminGroups.stream().filter(g -> g != null && !g.isBlank()).toList();
            if (publicUrl != null) publicUrl = trim(publicUrl);
            if (internalUrl != null) internalUrl = trim(internalUrl);
        }

        public String issuer() { return publicUrl + "/realms/" + realm; }
        public String authorizationUri() { return issuer() + "/protocol/openid-connect/auth"; }
        public String endSessionUri() { return issuer() + "/protocol/openid-connect/logout"; }
        private String backchannel() { return (internalUrl == null ? publicUrl : internalUrl) + "/realms/" + realm; }
        public String tokenUri() { return backchannel() + "/protocol/openid-connect/token"; }
        public String jwkSetUri() { return backchannel() + "/protocol/openid-connect/certs"; }
        public String userInfoUri() { return backchannel() + "/protocol/openid-connect/userinfo"; }
    }

    /** A provider brokered by Keycloak (e.g. alias "adfs"), shown as its own sign-in button. */
    public record IdentityProvider(String alias, String label) {}

    /**
     * Pulls users and groups from Keycloak's admin API so AD groups exist in NEXORA before anyone signs in.
     */
    public record Sync(boolean enabled, String clientId, String clientSecret, Duration interval) {
        public Sync {
            if (clientId == null || clientId.isBlank()) clientId = "nexora-sync";
            if (interval == null) interval = Duration.ofMinutes(15);
        }
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
