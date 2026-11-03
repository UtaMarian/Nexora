package io.nexora.office;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies WOPI access tokens: {@code base64url(user|file|expiry).base64url(HMAC-SHA256)}.
 * A token only identifies who is editing which file; permissions are re-checked on every WOPI call,
 * so revoking access takes effect immediately even for an open editor.
 */
@Service
public class WopiTokenService {
    private static final Logger log = LoggerFactory.getLogger(WopiTokenService.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] key;
    private final OfficeProperties props;

    public WopiTokenService(OfficeProperties props) {
        this.props = props;
        if (props.tokenSecret() == null || props.tokenSecret().isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = random;
            log.warn("nexora.office.token-secret is not set; using a random key. Open editors stop saving after a "
                    + "restart, and multiple server nodes will not accept each other's tokens.");
        } else {
            this.key = props.tokenSecret().getBytes(StandardCharsets.UTF_8);
        }
    }

    public record Claims(UUID userId, UUID fileId, Instant expiresAt, String origin) {}

    private static final java.util.regex.Pattern ORIGIN = java.util.regex.Pattern.compile("^https?://[A-Za-z0-9.-]+(:\\d{1,5})?$");

    /** @param origin the browser origin hosting the editor frame (for postMessage), or null */
    public String issue(UUID userId, UUID fileId, String origin) {
        Instant exp = Instant.now().plus(props.tokenTtl());
        String o = origin != null && ORIGIN.matcher(origin).matches() ? origin : "";
        String payload = userId + "|" + fileId + "|" + exp.toEpochMilli() + "|" + o;
        String body = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + B64.encodeToString(sign(body));
    }

    public Optional<Claims> verify(String token, UUID fileId) {
        if (token == null) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0) return Optional.empty();
        String body = token.substring(0, dot);
        try {
            byte[] sig = B64D.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(sig, sign(body))) return Optional.empty();
            String[] parts = new String(B64D.decode(body), StandardCharsets.UTF_8).split("\\|", -1);
            Claims c = new Claims(UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                    Instant.ofEpochMilli(Long.parseLong(parts[2])),
                    parts.length > 3 && !parts[3].isEmpty() ? parts[3] : null);
            if (!c.fileId().equals(fileId) || c.expiresAt().isBefore(Instant.now())) return Optional.empty();
            return Optional.of(c);
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
