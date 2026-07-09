package ooo.klae.connex.backend.secrets;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;
import lombok.ToString;

/**
 * Central key-encryption-key configuration for database-backed integration
 * secrets. The key protects generated per-secret data keys; feature code should
 * never read deployment-specific key providers directly.
 */
@Data
@ToString(exclude = { "masterKey", "keys" })
@Component
@ConfigurationProperties(prefix = "connex.secret-store")
public class SecretStoreProperties {

    /**
     * Stable operator-assigned identifier for the configured key-encryption key.
     */
    private String keyId = "local-v1";

    /**
     * Base64-encoded AES key (128/192/256-bit) used to wrap generated data keys.
     */
    private String masterKey;

    /**
     * Optional Base64-encoded AES keyring keyed by operator-assigned key id.
     */
    private Map<String, String> keys = new LinkedHashMap<>();
}
