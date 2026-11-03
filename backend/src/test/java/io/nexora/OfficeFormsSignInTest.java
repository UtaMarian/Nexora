package io.nexora;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** nexora.dav.office-sign-in=forms: desktop Office gets MS-OFBA, other WebDAV clients keep Basic. */
@TestPropertySource(properties = "nexora.dav.office-sign-in=forms")
class OfficeFormsSignInTest extends ApiTestSupport {

    @Test
    void desktopOfficeGetsFormsBasedSignIn() throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .request("PROPFIND", java.net.URI.create("/dav/sites/"))
                .header("User-Agent", "Microsoft Office Word 2014 (16.0.20527) Windows NT 10.0")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getHeader("X-FORMS_BASED_AUTH_REQUIRED")).endsWith("/api/v1/auth/ofba/start");
        assertThat(res.getHeader("X-FORMS_BASED_AUTH_RETURN_URL")).endsWith("/api/v1/auth/ofba/done");
        assertThat(res.getHeader("X-FORMS_BASED_AUTH_DIALOG_SIZE")).isNotBlank();

        var explorer = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .request("PROPFIND", java.net.URI.create("/dav/sites/"))
                .header("User-Agent", "Microsoft-WebDAV-MiniRedir/10.0.26300")).andReturn().getResponse();
        assertThat(explorer.getStatus()).isEqualTo(401);
        assertThat(explorer.getHeader("WWW-Authenticate")).startsWith("Basic");
    }
}
