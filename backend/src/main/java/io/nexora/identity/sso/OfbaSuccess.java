package io.nexora.identity.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;

/**
 * After single sign-on: back to the web app, or, when the sign-in was started from desktop Office (MS-OFBA),
 * to the OFBA return page so Office can close its sign-in window and use the new session.
 */
public class OfbaSuccess implements AuthenticationSuccessHandler {
    public static final String SESSION_FLAG = "nexora.ofba";

    @Override
    public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse res, Authentication auth)
            throws IOException {
        HttpSession session = req.getSession(false);
        boolean office = session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_FLAG));
        res.sendRedirect(office ? "/api/v1/auth/ofba/done" : "/");
    }
}
