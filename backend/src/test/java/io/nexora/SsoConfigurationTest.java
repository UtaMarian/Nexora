package io.nexora;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** With SSO switched on (no Keycloak needed): sign-in redirects, the ADFS hint and bearer-token rejection. */
@TestPropertySource(properties = {
        "nexora.identity.oidc.enabled=true",
        "nexora.identity.oidc.public-url=https://sso.example.test/auth",
        "nexora.identity.oidc.internal-url=http://127.0.0.1:9/auth",
        "nexora.identity.oidc.client-secret=test",
        "nexora.identity.oidc.identity-providers[0].alias=adfs",
        "nexora.identity.oidc.identity-providers[0].label=Sign in with ADFS",
})
class SsoConfigurationTest extends ApiTestSupport {

    @Test
    void signInRedirectsToKeycloakWithPkce() throws Exception {
        MvcResult r = mvc.perform(get("/oauth2/authorization/keycloak")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        String location = r.getResponse().getHeader("Location");
        assertThat(location).startsWith("https://sso.example.test/auth/realms/nexora/protocol/openid-connect/auth?")
                .contains("client_id=nexora").contains("code_challenge=").contains("code_challenge_method=S256")
                .contains("scope=openid").doesNotContain("kc_idp_hint");
    }

    @Test
    void adfsButtonSkipsTheKeycloakPage() throws Exception {
        String location = mvc.perform(get("/oauth2/authorization/keycloak").param("idp", "adfs"))
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).contains("kc_idp_hint=adfs");

        String unknown = mvc.perform(get("/oauth2/authorization/keycloak").param("idp", "evil"))
                .andReturn().getResponse().getHeader("Location");
        assertThat(unknown).doesNotContain("kc_idp_hint");
    }

    @Test
    void providersListSsoOptions() throws Exception {
        JsonNode p = json.readTree(mvc.perform(get("/api/v1/auth/providers")).andReturn().getResponse().getContentAsString());
        assertThat(p.get("sso")).extracting(o -> o.get("label").asText())
                .containsExactly("Organization account", "Sign in with ADFS");
        assertThat(p.get("sso").get(1).get("url").asText()).isEqualTo("/oauth2/authorization/keycloak?idp=adfs");
    }

    @Test
    void invalidBearerTokensAreRejected() throws Exception {
        int status = mvc.perform(get("/api/v1/sites").header("Authorization", "Bearer not-a-jwt"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
        // local Basic auth keeps working next to SSO
        int basic = mvc.perform(as(ADMIN, get("/api/v1/sites"))).andReturn().getResponse().getStatus();
        assertThat(basic).isEqualTo(200);
    }
}
