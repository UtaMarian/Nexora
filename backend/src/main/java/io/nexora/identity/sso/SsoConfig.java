package io.nexora.identity.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexora.audit.AuditService;
import io.nexora.identity.PrincipalRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * OpenID Connect against Keycloak. Browser-facing endpoints use the public URL; token, user info and key
 * endpoints go over the internal network. The token issuer is always the public URL.
 */
@Configuration
@ConditionalOnProperty(prefix = "nexora.identity.oidc", name = "enabled", havingValue = "true")
public class SsoConfig {
    public static final String REGISTRATION_ID = "keycloak";

    /** Everything the security filter chains need to turn SSO on. */
    public record SsoSecurity(ClientRegistrationRepository registrations, NexoraOidcUserService userService,
                              IdpHintRequestResolver requestResolver, JwtDecoder jwtDecoder,
                              JwtPrincipalConverter jwtConverter, IssuerScopedBearerTokenResolver bearerResolver) {}

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(IdentityProperties props) {
        IdentityProperties.Oidc c = props.oidc();
        ClientRegistration reg = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientName(c.displayName())
                .clientId(c.clientId())
                .clientSecret(c.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri(c.authorizationUri())
                .tokenUri(c.tokenUri())
                .jwkSetUri(c.jwkSetUri())
                .userInfoUri(c.userInfoUri())
                .userNameAttributeName("preferred_username")
                .issuerUri(c.issuer())
                .providerConfigurationMetadata(Map.of("end_session_endpoint", c.endSessionUri()))
                .build();
        return new InMemoryClientRegistrationRepository(reg);
    }

    @Bean
    SsoSecurity ssoSecurity(ClientRegistrationRepository registrations, DirectoryProvisioner provisioner,
                            IdentityProperties props, AuditService audit) {
        IdentityProperties.Oidc c = props.oidc();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(c.jwkSetUri()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(c.issuer()),
                jwt -> jwt.getAudience().contains(c.clientId()) || c.clientId().equals(jwt.getClaimAsString("azp"))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token is not for NEXORA", null))));
        return new SsoSecurity(registrations,
                new NexoraOidcUserService(provisioner, c, audit),
                new IdpHintRequestResolver(registrations,
                        c.identityProviders().stream().map(IdentityProperties.IdentityProvider::alias).collect(Collectors.toSet())),
                decoder,
                new JwtPrincipalConverter(provisioner, c),
                new IssuerScopedBearerTokenResolver(c.issuer()));
    }

    /**
     * Wrapped rather than exposed as an AuthenticationProvider bean: Spring Security would otherwise build its
     * global AuthenticationManager from that bean alone and drop local accounts.
     */
    public record DirectoryBasicAuth(DirectoryPasswordAuthenticationProvider provider) {}

    @Bean
    @ConditionalOnProperty(prefix = "nexora.identity.oidc", name = "directory-basic-auth", havingValue = "true", matchIfMissing = true)
    DirectoryBasicAuth directoryBasicAuth(IdentityProperties props, DirectoryProvisioner provisioner,
                                          PrincipalRepository principals, ObjectMapper json) {
        return new DirectoryBasicAuth(new DirectoryPasswordAuthenticationProvider(props.oidc(), provisioner, principals, json));
    }
}
