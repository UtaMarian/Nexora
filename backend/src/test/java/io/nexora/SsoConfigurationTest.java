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
    void foreignBearerTokensFromOfficeAreIgnoredOnWebDav() throws Exception {
        // what desktop Office sends: its own Microsoft 365 token (another issuer)
        String header = "eyJhbGciOiJSUzI1NiJ9";
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"https://sts.windows.net/tenant/\",\"aud\":\"office\"}".getBytes());
        String foreign = "Bearer " + header + "." + payload + ".c2ln";

        var options = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .options("/dav/sites/").header("Authorization", foreign)).andReturn().getResponse();
        assertThat(options.getStatus()).isNotEqualTo(401);

        var propfind = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .request("PROPFIND", java.net.URI.create("/dav/sites/")).header("Authorization", foreign))
                .andReturn().getResponse();
        assertThat(propfind.getStatus()).isEqualTo(401);
        assertThat(propfind.getHeader("WWW-Authenticate")).startsWith("Basic");   // so Office asks for a password

        var empty = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .request("PROPFIND", java.net.URI.create("/dav/sites/")).header("Authorization", "Bearer"))
                .andReturn().getResponse();
        assertThat(empty.getHeader("WWW-Authenticate")).startsWith("Basic");
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

    @Test
    void desktopOfficeGetsFormsBasedSignInInsteadOfBasic() throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .request("PROPFIND", java.net.URI.create("/dav/sites/"))
                .header("User-Agent", "Microsoft Office Word 2014 (16.0.20527) Windows NT 10.0")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getHeader("X-FORMS_BASED_AUTH_REQUIRED")).endsWith("/api/v1/auth/ofba/start");
        assertThat(res.getHeader("X-FORMS_BASED_AUTH_RETURN_URL")).endsWith("/api/v1/auth/ofba/done");
        assertThat(res.getHeader("X-FORMS_BASED_AUTH_DIALOG_SIZE")).isNotBlank();

        // Explorer's WebDAV redirector does not support OFBA: Basic challenge
        var explorer = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .request("PROPFIND", java.net.URI.create("/dav/sites/"))
                .header("User-Agent", "Microsoft-WebDAV-MiniRedir/10.0.26300")).andReturn().getResponse();
        assertThat(explorer.getStatus()).isEqualTo(401);
        assertThat(explorer.getHeader("WWW-Authenticate")).startsWith("Basic");

        // the sign-in page sends Office through single sign-on and ends on the return page
        var start = mvc.perform(get("/api/v1/auth/ofba/start")).andReturn();
        assertThat(start.getResponse().getStatus()).isEqualTo(302);
        var session = (org.springframework.mock.web.MockHttpSession) start.getRequest().getSession(false);
        assertThat(session.getAttribute("nexora.ofba")).isEqualTo(Boolean.TRUE);
        var done = mvc.perform(get("/api/v1/auth/ofba/done").session(session)).andReturn().getResponse();
        assertThat(done.getStatus()).isEqualTo(200);
        assertThat(done.getContentAsString()).contains("signed in");
    }

    @Test
    void webDavAcceptsTheSessionOfficeGotFromSignIn() throws Exception {
        var login = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/auth/login").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json").content("{\"login\":\"admin\",\"password\":\"admin-test-pw\"}")).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        var session = (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false);
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .request("PROPFIND", java.net.URI.create("/dav/sites/")).header("Depth", "0")
                .header("User-Agent", "Microsoft Office Word 2014").session(session)).andReturn().getResponse();
        // MockMvc stops at Spring's dispatcher (the WebDAV servlet is separate): passing security is what counts here
        assertThat(res.getStatus()).isNotIn(401, 403);
    }
}
