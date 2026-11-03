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
     * WebDAV clients (Office, Windows WebClient, macOS Finder, davfs2) authenticate on every request and keep no
     * cookies: HTTP Basic with a proper challenge, no session, no CSRF. OPTIONS is open so clients can discover
     * WebDAV support before they authenticate. Kerberos/NTLM SSO comes with the identity gateway.
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
                .exceptionHandling(e -> e.authenticationEntryPoint(challenge))
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        sso.ifAvailable(s -> {
            try {
                http.oauth2ResourceServer(o -> o.jwt(j -> j.decoder(s.jwtDecoder()).jwtAuthenticationConverter(s.jwtConverter())));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return http.build();
    }
}
