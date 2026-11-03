package io.nexora.office;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WopiDiscoveryTest {

    private static final String XML = """
            <wopi-discovery><net-zone name="external-http">
              <app name="writer">
                <action default="true" ext="docx" name="edit" urlsrc="http://localhost:9980/browser/abc/cool.html?"/>
                <action ext="doc" name="edit" urlsrc="http://localhost:9980/browser/abc/cool.html?"/>
              </app>
              <app name="application/pdf">
                <action ext="pdf" name="view" urlsrc="http://localhost:9980/browser/abc/cool.html?&lt;ui=UI_LLCC&amp;&gt;"/>
              </app>
            </net-zone></wopi-discovery>
            """;

    @Test
    void parsesActionsPerExtension() throws Exception {
        Map<String, Map<String, String>> actions =
                WopiDiscovery.parse(new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)));
        assertThat(actions).containsKeys("docx", "doc", "pdf");
        assertThat(actions.get("docx").get("edit")).isEqualTo("http://localhost:9980/browser/abc/cool.html?");
        assertThat(actions.get("pdf")).containsOnlyKeys("view");
        assertThat(actions.get("pdf").get("view")).isEqualTo("http://localhost:9980/browser/abc/cool.html?");
    }

    @Test
    void stripsPlaceholders() {
        assertThat(WopiDiscovery.stripPlaceholders("http://x/cool.html?<ui=UI_LLCC&><rs=DC_LLCC&>"))
                .isEqualTo("http://x/cool.html?");
        assertThat(WopiDiscovery.stripPlaceholders("http://x/cool.html?a=1")).isEqualTo("http://x/cool.html?a=1&");
        assertThat(WopiDiscovery.stripPlaceholders("http://x/cool.html")).isEqualTo("http://x/cool.html?");
    }
}
