package ooo.klae.connex.backend.secrets;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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

    /**
     * Optional lifecycle metadata keyed by key id. Values are non-secret and are
     * surfaced in admin diagnostics.
     */
    private Map<String, KeyMetadata> metadata = new LinkedHashMap<>();

    /**
     * Key ids that must fail closed even if key material is still configured.
     */
    private Set<String> disabledKeyIds = new LinkedHashSet<>();

    /**
     * Opportunistically rewrap old-key rows to the active key after successful reads.
     */
    private boolean lazyRewrapEnabled = true;

    /**
     * Batch rewrap stale rows during startup. Disabled by default; enable during rotation windows.
     */
    private boolean batchRewrapOnStartup = false;

    /**
     * Maximum stale rows the startup batch rewrap attempts in one boot.
     */
    private int batchRewrapLimit = 1000;

    /**
     * Non-secret lifecycle metadata for an envelope key-encryption key.
     */
    @Data
    public static class KeyMetadata {
        private String version;
        private String algorithm = "AES-GCM";
        private String owner = "operator";
        private String scope = "instance";
        private String createdAt;
        private String rotatedAt;
        private String disabledAt;
    }
}
