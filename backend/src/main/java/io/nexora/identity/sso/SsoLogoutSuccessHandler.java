package io.nexora.identity.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Ends the NEXORA session. For SSO users the JSON response carries Keycloak's end-session URL, so the web app
 * can also end the Keycloak (and, through it, ADFS) session; otherwise signing out would sign straight back in.
 */
public class SsoLogoutSuccessHandler implements LogoutSuccessHandler {
    private final IdentityProperties.Oidc cfg;

    public SsoLogoutSuccessHandler(IdentityProperties.Oidc cfg) {
        this.cfg = cfg;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest req, HttpServletResponse res, Authentication auth) throws IOException {
        if (auth != null && auth.getPrincipal() instanceof OidcUser oidc && cfg != null && cfg.enabled()) {
            String back = cfg.postLogoutRedirectUri() != null ? cfg.postLogoutRedirectUri()
                    : UriComponentsBuilder.fromUriString(req.getRequestURL().toString()).replacePath("/").replaceQuery(null).toUriString();
            String url = UriComponentsBuilder.fromUriString(cfg.endSessionUri())
                    .queryParam("id_token_hint", oidc.getIdToken().getTokenValue())
                    .queryParam("post_logout_redirect_uri", back)
                    .encode().toUriString();
            res.setStatus(200);
            res.setContentType("application/json");
            res.getWriter().write("{\"redirect\":\"" + url.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
            return;
        }
        res.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
