package ooo.klae.connex.backend.delivery;

import java.util.Map;
import java.util.Objects;

/**
 * Decrypted credential material for a workspace-scoped delivery provider. The SMTP provider carries
 * no credential material of its own (it re-resolves the workspace mail transport), but the bundle
 * exists so a future API-keyed provider drops into the same seam.
 * @param values provider-specific credential values
 */
public record DeliveryCredentials(Map<String, String> values) {

    public DeliveryCredentials {
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    /**
     * Creates an immutable provider credential bundle.
     * @param values provider-specific credential values
     * @return immutable credential bundle
     */
    public static DeliveryCredentials of(Map<String, String> values) {
        return new DeliveryCredentials(values);
    }

    /**
     * Creates an empty credential bundle for providers that carry none.
     * @return empty credential bundle
     */
    public static DeliveryCredentials none() {
        return new DeliveryCredentials(Map.of());
    }

    /**
     * Returns an optional credential value.
     * @param key provider credential key
     * @return credential value, or null when absent
     */
    public String get(String key) {
        if (key == null) {
            return null;
        }
        return values.get(key);
    }

    /**
     * Returns a required non-blank credential value.
     * @param key provider credential key
     * @return credential value
     * @throws DeliveryProviderException when the value is absent or blank
     */
    public String require(String key) {
        if (key == null || key.isBlank()) {
            throw new DeliveryProviderException("Delivery provider credential key is required");
        }
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new DeliveryProviderException("Delivery provider credential " + key + " is required");
        }
        return value;
    }

    @Override
    public String toString() {
        return "DeliveryCredentials[values=<redacted>]";
    }
}
