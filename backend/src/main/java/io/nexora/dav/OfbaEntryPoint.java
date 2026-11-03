package io.nexora.dav;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.util.Locale;

/**
 * Asks WebDAV clients to sign in.
 *
 * <p>Desktop Office (Microsoft 365 Apps) blocks Basic-authentication prompts, so Office gets MS-OFBA (Office
 * Forms Based Authentication): a 403 naming a sign-in page that Office opens in its own browser window. The user
 * signs in there with single sign-on (Keycloak, AD/LDAP, Kerberos, AD FS), and Office reuses that session cookie
 * for its WebDAV requests. Other clients (Windows Explorer's WebDAV redirector, macOS, Linux, scripts) get a Basic
 * challenge.
 */
public class OfbaEntryPoint implements AuthenticationEntryPoint {
    static final String START = "/api/v1/auth/ofba/start";
    static final String DONE = "/api/v1/auth/ofba/done";

    private final AuthenticationEntryPoint basic;

    public OfbaEntryPoint(AuthenticationEntryPoint basic) {
        this.basic = basic;
    }

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException e)
            throws IOException, jakarta.servlet.ServletException {
        if (!isOfbaClient(req)) {
            basic.commence(req, res, e);
            return;
        }
        String base = ServletUriComponentsBuilder.fromContextPath(req).replacePath(null).replaceQuery(null).toUriString();
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setHeader("X-FORMS_BASED_AUTH_REQUIRED", base + START);
        res.setHeader("X-FORMS_BASED_AUTH_RETURN_URL", base + DONE);
        res.setHeader("X-FORMS_BASED_AUTH_DIALOG_SIZE", "800x680");
        res.setContentLength(0);
    }

    /** Office announces MS-OFBA support with a header, or can be recognised by its user agent. */
    static boolean isOfbaClient(HttpServletRequest req) {
        String accepted = req.getHeader("X-FORMS_BASED_AUTH_ACCEPTED");
        if (accepted != null) return accepted.trim().equalsIgnoreCase("t");
        String ua = req.getHeader("User-Agent");
        if (ua == null) return false;
        String u = ua.toLowerCase(Locale.ROOT);
        if (u.contains("microsoft-webdav-miniredir")) return false;   // Explorer's redirector: no OFBA support
        return u.contains("microsoft office") || u.contains("ms-office") || u.contains("msoffice")
                || u.contains("microsoft office protocol discovery");
    }
}
