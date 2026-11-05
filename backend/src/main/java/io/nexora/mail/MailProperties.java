package io.nexora.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param publicUrl the address people use to reach NEXORA, for links in email
 * @param workerEnabled send from this server; turn off on all but one when several share a database
 */
@ConfigurationProperties(prefix = "nexora.mail")
public record MailProperties(boolean enabled, String host, int port, String username, String password, String from,
                             boolean startTls, String publicUrl, boolean workerEnabled) {
}
