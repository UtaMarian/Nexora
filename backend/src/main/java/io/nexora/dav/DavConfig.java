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
     * WebDAV sign-in:
     * <ul>
     *   <li>desktop Office: MS-OFBA (403 + sign-in page in Office's own browser window, then the NEXORA session
     *       cookie), because Microsoft 365 Apps block Basic-authentication prompts;</li>
     *   <li>Windows Explorer, macOS, Linux, scripts: HTTP Basic (local or directory password) or a NEXORA bearer token.</li>
     * </ul>
     * An existing session is used but never created here. CSRF protection is not needed: WebDAV's write methods
     * cannot be sent cross-site without a CORS preflight (none is allowed), and the session cookie is SameSite=Lax.
     * OPTIONS is open so clients can discover WebDAV support before they authenticate.
     */
    @Bean
    @Order(1)
    SecurityFilterChain davSecurity(HttpSecurity http,
            org.springframework.beans.factory.ObjectProvider<io.nexora.identity.sso.SsoConfig.SsoSecurity> sso,
            io.nexora.identity.SecurityConfig.AuthProviders authProviders) throws Exception {
        BasicAuthenticationEntryPoint challenge = new BasicAuthenticationEntryPoint();
        challenge.setRealmName("NEXORA");
        challenge.afterPropertiesSet();
        // Path-pattern matchers: the MVC-based default does not see requests served by the WebDAV servlet.
        var paths = PathPatternRequestMatcher.withDefaults();
        authProviders.providers().forEach(http::authenticationProvider);
        http.securityMatcher(paths.matcher("/dav/**"))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(paths.matcher(HttpMethod.OPTIONS, "/dav/**")).permitAll()
                        .anyRequest().authenticated())
                .httpBasic(b -> b.authenticationEntryPoint(challenge))
                .exceptionHandling(e -> e.authenticationEntryPoint(new OfbaEntryPoint(challenge)))
                .csrf(c -> c.disable())
                .securityContext(c -> c.securityContextRepository(
                        new org.springframework.security.web.context.HttpSessionSecurityContextRepository()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.NEVER));
        sso.ifAvailable(s -> {
            try {
                // ignore bearer tokens from other issuers (desktop Office sends its Microsoft 365 token)
                http.oauth2ResourceServer(o -> o
                        .bearerTokenResolver(s.bearerResolver())
                        .authenticationEntryPoint(new OfbaEntryPoint(challenge))
                        .jwt(j -> j.decoder(s.jwtDecoder()).jwtAuthenticationConverter(s.jwtConverter())));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return http.build();
    }
}
