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
    private final io.nexora.search.SearchSwitch search;

    public AuthController(AuthenticationManager authManager, SecurityContextRepository contextRepository,
                          IdentityService identity, AuditService audit, io.nexora.identity.sso.IdentityProperties idp,
                          io.nexora.search.SearchSwitch search) {
        this.idp = idp;
        this.search = search;
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
        return new Me(p.getId(), user.anonymous() ? null : p.getLogin(), p.getDisplayName(), p.getEmail(), p.isAdmin(), search.enabled(), user.anonymous());
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

    /**
     * MS-OFBA sign-in page for desktop Office: opened in Office's own browser window. Signs the user in (single
     * sign-on when configured, otherwise the web app's sign-in page) and ends on {@link #ofbaDone}, which tells
     * Office it can close the window and use the session.
     */
    @GetMapping("/ofba/start")
    public void ofbaStart(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
        request.getSession(true).setAttribute(io.nexora.identity.sso.OfbaSuccess.SESSION_FLAG, Boolean.TRUE);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof io.nexora.identity.NexoraPrincipal) {
            response.sendRedirect("/api/v1/auth/ofba/done");
        } else if (idp.oidc().enabled() && !idp.localLogin()) {
            response.sendRedirect("/oauth2/authorization/" + io.nexora.identity.sso.SsoConfig.REGISTRATION_ID);
        } else {
            response.sendRedirect("/?ofba=1");   // sign-in page with SSO buttons and, if enabled, local accounts
        }
    }

    @GetMapping(path = "/ofba/done", produces = "text/html;charset=UTF-8")
    public String ofbaDone(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.removeAttribute(io.nexora.identity.sso.OfbaSuccess.SESSION_FLAG);
        return "<!doctype html><meta charset=utf-8><title>Signed in</title>"
                + "<body style=\"font-family:Segoe UI,sans-serif;padding:32px\"><h2>You are signed in to NEXORA</h2>"
                + "<p>You can return to Office.</p></body>";
    }

    /** Primes the XSRF-TOKEN cookie for the web app. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName());
    }
}
