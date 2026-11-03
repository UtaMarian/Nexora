package io.nexora.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Two ways in:
 * <ul>
 *   <li>Browser: session cookie created by {@code POST /api/v1/auth/login}, protected by a CSRF token
 *       (cookie {@code XSRF-TOKEN}, echoed back in header {@code X-XSRF-TOKEN}).</li>
 *   <li>Scripts and integrations: HTTP Basic on every request; these carry no ambient cookie, so CSRF
 *       does not apply to them.</li>
 * </ul>
 * OIDC/SAML/Kerberos via Keycloak plug in here later without touching the services.
 */
@Configuration
public class SecurityConfig {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Bean
    @org.springframework.core.annotation.Order(2)
    SecurityFilterChain api(HttpSecurity http,
                            org.springframework.beans.factory.ObjectProvider<io.nexora.identity.sso.SsoConfig.SsoSecurity> sso,
                            io.nexora.identity.sso.IdentityProperties identity,
                            AuthProviders authProviders) throws Exception {
        // WOPI calls come server-to-server from the Office editor and carry a signed access_token, not a cookie.
        RequestMatcher csrfRequired = request ->
                !SAFE_METHODS.contains(request.getMethod())
                        && request.getHeader("Authorization") == null
                        && !request.getRequestURI().startsWith("/wopi/");

        // Add (not replace) our providers, so the OIDC login and bearer-token providers Spring registers still apply.
        authProviders.providers().forEach(http::authenticationProvider);
        http
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/csrf", "/api/v1/auth/providers",
                        "/api/v1/auth/ofba/**").permitAll()
                .requestMatchers("/wopi/**").permitAll() // authenticated by WOPI access token in WopiController
                .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                .requireCsrfProtectionMatcher(csrfRequired))
            .httpBasic(basic -> basic.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .securityContext(ctx -> ctx.securityContextRepository(securityContextRepository()))
            .logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler(new io.nexora.identity.sso.SsoLogoutSuccessHandler(identity.oidc())))
            .formLogin(form -> form.disable());

        io.nexora.identity.sso.SsoConfig.SsoSecurity s = sso.getIfAvailable();
        if (s != null) {
            // Browser single sign-on (Authorization Code + PKCE) and bearer tokens for scripts and integrations.
            http.oauth2Login(o -> o
                    .clientRegistrationRepository(s.registrations())
                    .authorizationEndpoint(a -> a.authorizationRequestResolver(s.requestResolver()))
                    .userInfoEndpoint(u -> u.oidcUserService(s.userService()))
                    .securityContextRepository(securityContextRepository())
                    .successHandler(new io.nexora.identity.sso.OfbaSuccess())
                    .failureHandler((req, res, ex) -> {
                        String code = ex instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException o2
                                ? o2.getError().getErrorCode() : "login_failed";
                        res.sendRedirect("/?loginError=" + java.net.URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8));
                    }))
                .oauth2ResourceServer(o -> o
                    .bearerTokenResolver(s.bearerResolver())
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                    .jwt(j -> j.decoder(s.jwtDecoder()).jwtAuthenticationConverter(s.jwtConverter())));
        }
        return http.build();
    }

    /** Allows the WebDAV methods and '%' in paths (legal in SharePoint file names); everything else stays strict. */
    @Bean
    org.springframework.security.web.firewall.HttpFirewall httpFirewall() {
        org.springframework.security.web.firewall.StrictHttpFirewall fw =
                new org.springframework.security.web.firewall.StrictHttpFirewall();
        fw.setAllowedHttpMethods(java.util.List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS",
                "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK"));
        fw.setAllowUrlEncodedPercent(true);
        return fw;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Username/password providers: local accounts, then directory users (password checked by AD via Keycloak). */
    public record AuthProviders(java.util.List<org.springframework.security.authentication.AuthenticationProvider> providers) {}

    @Bean
    AuthProviders authProviders(DbUserDetailsService users, PasswordEncoder encoder,
            org.springframework.beans.factory.ObjectProvider<io.nexora.identity.sso.SsoConfig.DirectoryBasicAuth> directory) {
        DaoAuthenticationProvider local = new DaoAuthenticationProvider(users);
        local.setPasswordEncoder(encoder);
        java.util.List<org.springframework.security.authentication.AuthenticationProvider> providers = new java.util.ArrayList<>();
        providers.add(local);
        directory.ifAvailable(d -> providers.add(d.provider()));
        return new AuthProviders(java.util.List.copyOf(providers));
    }

    /** Used by the JSON login endpoint. */
    @Bean
    AuthenticationManager authenticationManager(AuthProviders authProviders) {
        return new ProviderManager(authProviders.providers());
    }

    /** CSRF handling for a single-page app, as recommended in the Spring Security reference. */
    static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            xor.handle(request, response, csrfToken);
            csrfToken.get(); // render the cookie on every response
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String header = request.getHeader(csrfToken.getHeaderName());
            return (StringUtils.hasText(header) ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
