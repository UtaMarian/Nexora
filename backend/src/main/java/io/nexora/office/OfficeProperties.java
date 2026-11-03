package io.nexora.office;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Browser Office editing through a WOPI client (Collabora Online / CODE).
 *
 * @param enabled      turn the integration on
 * @param discoveryUrl where NEXORA reads the editor's WOPI discovery XML, e.g. http://collabora:9980/hosting/discovery
 * @param wopiHostUrl  base URL the editor uses to call back into NEXORA, e.g. http://backend:8080
 * @param tokenSecret  HMAC key for access tokens; keep it stable across restarts and identical on all nodes
 * @param tokenTtl     how long an editor session token stays valid
 */
@ConfigurationProperties(prefix = "nexora.office")
public record OfficeProperties(boolean enabled, String discoveryUrl, String wopiHostUrl, String tokenSecret,
                               Duration tokenTtl) {

    public OfficeProperties {
        if (tokenTtl == null) tokenTtl = Duration.ofHours(10);
        if (wopiHostUrl != null && wopiHostUrl.endsWith("/")) wopiHostUrl = wopiHostUrl.substring(0, wopiHostUrl.length() - 1);
    }
}
