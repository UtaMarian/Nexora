package io.nexora.identity.sso;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.Set;

/**
 * Adds PKCE to every sign-in and, for {@code /oauth2/authorization/keycloak?idp=adfs}, Keycloak's
 * {@code kc_idp_hint} so users go straight to ADFS (or another brokered provider) without seeing Keycloak.
 */
public class IdpHintRequestResolver implements OAuth2AuthorizationRequestResolver {
    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final Set<String> allowed;

    public IdpHintRequestResolver(ClientRegistrationRepository repo, Set<String> allowedAliases) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(repo, "/oauth2/authorization");
        this.delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        this.allowed = allowedAliases;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return hint(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return hint(request, delegate.resolve(request, registrationId));
    }

    private OAuth2AuthorizationRequest hint(HttpServletRequest request, OAuth2AuthorizationRequest req) {
        if (req == null) return null;
        String idp = request.getParameter("idp");
        if (idp == null || !allowed.contains(idp)) return req;
        return OAuth2AuthorizationRequest.from(req).additionalParameters(p -> p.put("kc_idp_hint", idp)).build();
    }
}
