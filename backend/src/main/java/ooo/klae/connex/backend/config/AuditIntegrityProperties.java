package ooo.klae.connex.backend.config;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * App-held HMAC settings for tamper-evident audit-log row hashing.
 */
@Component
@ConfigurationProperties(prefix = "connex.audit.integrity")
public class AuditIntegrityProperties {

    private static final int MIN_HMAC_SECRET_LENGTH = 32;

    private String hmacSecret;

    public String getHmacSecret() {
        return hmacSecret;
    }

    public void setHmacSecret(String hmacSecret) {
        this.hmacSecret = hmacSecret;
    }

    public boolean hasValidHmacSecret() {
        return hmacSecret != null && hmacSecret.strip().length() >= MIN_HMAC_SECRET_LENGTH;
    }

    public byte[] hmacSecretBytes() {
        if (!hasValidHmacSecret()) {
            throw new IllegalStateException("Audit integrity HMAC secret must be at least 32 characters");
        }
        return hmacSecret.strip().getBytes(StandardCharsets.UTF_8);
    }
}
