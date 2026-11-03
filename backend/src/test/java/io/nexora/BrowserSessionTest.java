package io.nexora;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** The web app's flow: cookie session + CSRF token, no Authorization header. */
class BrowserSessionTest extends ApiTestSupport {

    @Test
    void loginCreatesASessionAndWritesRequireTheCsrfToken() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        Cookie xsrf = csrf.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();

        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("login", ADMIN, "password", ADMIN_PW))))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        assertThat(mvc.perform(get("/api/v1/auth/me").session(session)).andReturn().getResponse().getStatus())
                .isEqualTo(200);

        byte[] body = json.writeValueAsBytes(Map.of("url", "csrf-" + suffix));
        int withoutToken = mvc.perform(post("/api/v1/sites").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
        assertThat(withoutToken).isEqualTo(403);

        Cookie fresh = login.getResponse().getCookie("XSRF-TOKEN");
        Cookie token = fresh != null ? fresh : xsrf;
        int withToken = mvc.perform(post("/api/v1/sites").session(session).cookie(token)
                .header("X-XSRF-TOKEN", token.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
        assertThat(withToken).isEqualTo(201);
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        Cookie xsrf = csrf.getResponse().getCookie("XSRF-TOKEN");
        int status = mvc.perform(post("/api/v1/auth/login")
                        .cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("login", ADMIN, "password", "nope"))))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }
}
