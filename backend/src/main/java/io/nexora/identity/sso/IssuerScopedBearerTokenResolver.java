package io.nexora.identity.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Only picks up bearer tokens that NEXORA's own identity provider issued.
 *
 * <p>Desktop Office sends its Microsoft 365 / Entra ID token to every WebDAV server it talks to. If NEXORA tried
 * to validate it, the {@code WWW-Authenticate: Bearer error="invalid_token"} answer would make Office show its
 * Microsoft sign-in window again and again. Foreign tokens are therefore treated as "no credentials": the request
 * continues anonymously and gets NEXORA's normal Basic challenge. The issuer is read without verification only to
 * decide whose token it is; tokens we do pick up are fully verified afterwards.
 */
public class IssuerScopedBearerTokenResolver implements BearerTokenResolver {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
    private final String issuer;

    public IssuerScopedBearerTokenResolver(String issuer) {
        this.issuer = issuer;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String token;
        try {
            token = delegate.resolve(request);
        } catch (RuntimeException malformed) {
            return null;   // e.g. "Authorization: Bearer" with nothing after it
        }
        return token != null && issuedByUs(token) ? token : null;
    }

    boolean issuedByUs(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) return false;
        try {
            JsonNode claims = JSON.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            return issuer.equals(claims.path("iss").asText(null));
        } catch (Exception e) {
            return false;
        }
    }
}
