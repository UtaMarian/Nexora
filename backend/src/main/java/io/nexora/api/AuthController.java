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

    public AuthController(AuthenticationManager authManager, SecurityContextRepository contextRepository,
                          IdentityService identity, AuditService audit) {
        this.authManager = authManager;
        this.contextRepository = contextRepository;
        this.identity = identity;
        this.audit = audit;
    }

    /** Signs in and starts a browser session (cookie). Scripts should use HTTP Basic instead. */
    @PostMapping("/login")
    public Me login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
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

    /** Primes the XSRF-TOKEN cookie for the web app. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName());
    }
}
