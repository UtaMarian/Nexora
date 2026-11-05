package io.nexora.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Lets a request with no sign-in act as the anonymous visitor, when the farm allows it. Only for reading, and only for
 * the addresses a public page needs: anything not listed here still needs a real account, whatever a site's permissions
 * say. The visitor is never saved to a session.
 */
final class AnonymousVisitorFilter extends OncePerRequestFilter {
    private static final Pattern READABLE = Pattern.compile("^/api/v1/(" + String.join("|", List.of(
            "auth/me", "resolve", "search", "embed-hosts", "pages/resolve", "sites",
            "sites/[^/]+/(design|logo|libraries|lists|pages|news|subsites)",
            "pages/[^/]+",
            "nodes/[^/]+(/(children|columns|settings|views|covers|content))?")) + ")$");

    private final AnonymousAccess anonymous;

    AnonymousVisitorFilter(AnonymousAccess anonymous) {
        this.anonymous = anonymous;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean nobody = auth == null || auth instanceof AnonymousAuthenticationToken;
        if (nobody && readable(request) && anonymous.enabled()) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new Visitor(anonymous.visitor()), null, List.of()));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private static boolean readable(HttpServletRequest request) {
        String method = request.getMethod();
        return ("GET".equals(method) || "HEAD".equals(method))
                && request.getParameter("versionId") == null    // the published file, not its history
                && READABLE.matcher(request.getRequestURI()).matches();
    }

    private record Visitor(CurrentUser user) implements NexoraPrincipal {
        @Override public UUID getId() { return user.id(); }
        @Override public String getLogin() { return user.login(); }
        @Override public String getDisplayName() { return user.displayName(); }
        @Override public boolean isAdmin() { return false; }
    }
}
