package io.nexora.identity.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexora.identity.NexoraUserDetails;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP Basic for directory users (WebDAV clients, desktop Office, scripts): the password is checked by Keycloak,
 * and therefore by AD/LDAP, with an OAuth2 password grant. Successful checks are cached for a few minutes, keyed
 * by a hash, because WebDAV clients send credentials on every request.
 */
public class DirectoryPasswordAuthenticationProvider implements AuthenticationProvider {
    private static final Logger log = LoggerFactory.getLogger(DirectoryPasswordAuthenticationProvider.class);
    private static final Duration CACHE = Duration.ofMinutes(5);

    private final IdentityProperties.Oidc cfg;
    private final DirectoryProvisioner provisioner;
    private final PrincipalRepository principals;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Instant> verified = new ConcurrentHashMap<>();

    public DirectoryPasswordAuthenticationProvider(IdentityProperties.Oidc cfg, DirectoryProvisioner provisioner,
                                                   PrincipalRepository principals, ObjectMapper json) {
        this.cfg = cfg;
        this.provisioner = provisioner;
        this.principals = principals;
        this.json = json;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String login = authentication.getName();
        String password = String.valueOf(authentication.getCredentials());
        Principal local = principals.findByKindAndLogin(Principal.Kind.USER, login).orElse(null);
        if (local != null && local.getPasswordHash() != null) return null;   // local account: not ours

        String key = hash(login.toLowerCase() + "\u0000" + password);
        Instant until = verified.get(key);
        Principal p;
        if (until != null && until.isAfter(Instant.now()) && local != null) {
            p = local;
        } else {
            p = verifyWithKeycloak(login, password);
            verified.put(key, Instant.now().plus(CACHE));
        }
        if (!p.isEnabled()) throw new DisabledException("Account disabled");
        NexoraUserDetails details = new NexoraUserDetails(p);
        return UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities());
    }

    private Principal verifyWithKeycloak(String login, String password) {
        try {
            String form = "grant_type=password&scope=openid"
                    + "&client_id=" + enc(cfg.clientId()) + "&client_secret=" + enc(cfg.clientSecret())
                    + "&username=" + enc(login) + "&password=" + enc(password);
            HttpResponse<String> tok = http.send(HttpRequest.newBuilder(URI.create(cfg.tokenUri()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            if (tok.statusCode() != 200) throw new BadCredentialsException("Bad credentials");
            String access = json.readTree(tok.body()).path("access_token").asText();
            HttpResponse<String> info = http.send(HttpRequest.newBuilder(URI.create(cfg.userInfoUri()))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + access).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (info.statusCode() != 200) throw new BadCredentialsException("Bad credentials");
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = json.readValue(info.body(), Map.class);
            return provisioner.provision(Claims.toUser(claims, cfg));
        } catch (AuthenticationException e) {
            throw e;
        } catch (DirectoryProvisioner.AccountConflictException e) {
            throw new BadCredentialsException(e.getMessage());
        } catch (Exception e) {
            log.warn("Directory password check failed: {}", e.toString());
            throw new BadCredentialsException("Directory unavailable");
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
