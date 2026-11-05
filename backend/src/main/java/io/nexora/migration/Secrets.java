package io.nexora.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts the passwords, tokens and cookies NEXORA keeps for source farms (AES-256-GCM). The key comes from
 * {@code nexora.admin.secret-key} (falls back to the WOPI token secret); keep it stable, or stored credentials
 * have to be entered again.
 */
@Component
public class Secrets {
    private static final Logger log = LoggerFactory.getLogger(Secrets.class);
    private static final String PREFIX = "v1:";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public Secrets(@Value("${nexora.admin.secret-key:}") String configured,
                   @Value("${nexora.office.token-secret:}") String fallback) {
        String material = !configured.isBlank() ? configured : fallback;
        if (material.isBlank()) {
            log.warn("No nexora.admin.secret-key is set: migration credentials are encrypted with a per-process key "
                    + "and must be entered again after a restart");
            byte[] b = new byte[32];
            random.nextBytes(b);
            material = Base64.getEncoder().encodeToString(b);
        }
        try {
            byte[] k = MessageDigest.getInstance("SHA-256").digest(("nexora-secrets:" + material).getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(k, "AES");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return null;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("The stored credential cannot be decrypted (was nexora.admin.secret-key changed?)");
        }
    }
}
