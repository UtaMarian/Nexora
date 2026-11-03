package io.nexora.dav;

import io.nexora.acl.PermissionService;
import io.nexora.content.ContentService;
import io.nexora.content.DocumentService;
import io.nexora.content.EditLockService;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
public class DavConfig {

    @Bean
    ServletRegistrationBean<DavServlet> davServlet(ContentService content, DocumentService documents,
                                                   EditLockService locks, PermissionService permissions,
                                                   GhostStore ghosts) {
        ServletRegistrationBean<DavServlet> reg = new ServletRegistrationBean<>(
                new DavServlet(content, documents, locks, permissions, ghosts), "/dav", "/dav/*");
        reg.setName("webdav");
        reg.setLoadOnStartup(1);
        return reg;
    }

    /**
     * WebDAV sign-in: HTTP Basic (local or directory password, shown by Windows as its credential prompt) or a
     * NEXORA bearer token. With {@code nexora.dav.office-sign-in=forms}, desktop Office gets MS-OFBA instead (403 +
     * a sign-in page in Office's own browser window, then the NEXORA session cookie). Microsoft 365 Apps block
     * Basic prompts unless the host is allowed by the "Allow specified hosts to show Basic Authentication prompts
     * to Office apps" policy, so pick {@code forms} when that policy cannot be deployed.
     * An existing session is used but never created here. CSRF protection is not needed: WebDAV's write methods
     * cannot be sent cross-site without a CORS preflight (none is allowed), and the session cookie is SameSite=Lax.
     * OPTIONS is open so clients can discover WebDAV support before they authenticate.
     */
    @Bean
    @Order(1)
    SecurityFilterChain davSecurity(HttpSecurity http,
            org.springframework.beans.factory.ObjectProvider<io.nexora.identity.sso.SsoConfig.SsoSecurity> sso,
            io.nexora.identity.SecurityConfig.AuthProviders authProviders,
            @org.springframework.beans.factory.annotation.Value("${nexora.dav.office-sign-in:basic}") String officeSignIn)
            throws Exception {
        BasicAuthenticationEntryPoint challenge = new BasicAuthenticationEntryPoint();
        challenge.setRealmName("NEXORA");
        challenge.afterPropertiesSet();
        org.springframework.security.web.AuthenticationEntryPoint entry =
                "forms".equalsIgnoreCase(officeSignIn.trim()) ? new OfbaEntryPoint(challenge) : challenge;
        // Path-pattern matchers: the MVC-based default does not see requests served by the WebDAV servlet.
        var paths = PathPatternRequestMatcher.withDefaults();
        authProviders.providers().forEach(http::authenticationProvider);
        http.securityMatcher(paths.matcher("/dav/**"))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(paths.matcher(HttpMethod.OPTIONS, "/dav/**")).permitAll()
                        .anyRequest().authenticated())
                .httpBasic(b -> b.authenticationEntryPoint(challenge))
                .exceptionHandling(e -> e.authenticationEntryPoint(entry))
                .csrf(c -> c.disable())
                .securityContext(c -> c.securityContextRepository(
                        new org.springframework.security.web.context.HttpSessionSecurityContextRepository()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.NEVER));
        sso.ifAvailable(s -> {
            try {
                // ignore bearer tokens from other issuers (desktop Office sends its Microsoft 365 token)
                http.oauth2ResourceServer(o -> o
                        .bearerTokenResolver(s.bearerResolver())
                        .authenticationEntryPoint(entry)
                        .jwt(j -> j.decoder(s.jwtDecoder()).jwtAuthenticationConverter(s.jwtConverter())));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return http.build();
    }
}
