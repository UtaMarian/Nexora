package io.nexora.api;

import io.nexora.api.Dtos.LoginRequest;
import io.nexora.api.Dtos.Me;
import io.nexora.audit.AuditService;
import io.nexora.common.ApiException;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.IdentityService;
import io.nexora.identity.Principal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepository;
    private final IdentityService identity;
    private final AuditService audit;
    private final io.nexora.identity.sso.IdentityProperties idp;

    public AuthController(AuthenticationManager authManager, SecurityContextRepository contextRepository,
                          IdentityService identity, AuditService audit, io.nexora.identity.sso.IdentityProperties idp) {
        this.idp = idp;
        this.authManager = authManager;
        this.contextRepository = contextRepository;
        this.identity = identity;
        this.audit = audit;
    }

    /** Signs in and starts a browser session (cookie). Scripts should use HTTP Basic instead. */
    @PostMapping("/login")
    public Me login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        if (!idp.localLogin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "local_login_disabled", "Sign in with your organization account");
        }
        Authentication auth;
        try {
            auth = authManager.authenticate(new UsernamePasswordAuthenticationToken(body.login(), body.password()));
        } catch (AuthenticationException e) {
            audit.record(null, "LOGIN_FAILED", null, Map.of("login", body.login()));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_credentials", "Wrong user name or password");
        }
        request.getSession(true);
        request.changeSessionId(); // session fixation protection
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, request, response);
        CurrentUser user = CurrentUser.get();
        audit.record(user, "LOGIN", null);
        return me();
    }

    @GetMapping("/me")
    public Me me() {
        CurrentUser user = CurrentUser.get();
        Principal p = identity.get(user.id());
        return new Me(p.getId(), p.getLogin(), p.getDisplayName(), p.getEmail(), p.isAdmin());
    }

    public record SsoOption(String id, String label, String url) {}

    public record Providers(boolean localLogin, java.util.List<SsoOption> sso) {}

    /** How people can sign in: shown on the login page before authentication. */
    @GetMapping("/providers")
    public Providers providers() {
        java.util.List<SsoOption> sso = new java.util.ArrayList<>();
        var oidc = idp.oidc();
        if (oidc.enabled()) {
            String base = "/oauth2/authorization/" + io.nexora.identity.sso.SsoConfig.REGISTRATION_ID;
            sso.add(new SsoOption("keycloak", oidc.displayName(), base));
            for (var p : oidc.identityProviders()) {
                sso.add(new SsoOption(p.alias(), p.label(), base + "?idp=" + java.net.URLEncoder.encode(p.alias(), java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
        return new Providers(idp.localLogin(), sso);
    }

    /** Primes the XSRF-TOKEN cookie for the web app. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName());
    }
}
