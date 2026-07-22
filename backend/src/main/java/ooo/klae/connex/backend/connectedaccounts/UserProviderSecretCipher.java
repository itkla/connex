package ooo.klae.connex.backend.connectedaccounts;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.secrets.SecretPurpose;
import ooo.klae.connex.backend.secrets.SecretStore;

/**
 * Secret-store facade for per-user provider token bundles (user scope, one bundle per
 * user + provider). Plaintext bundles exist only between this facade and the immediate consumer.
 */
@Component
@RequiredArgsConstructor
public class UserProviderSecretCipher {
    private final SecretStore secretStore;

    public boolean isAvailable() {
        return secretStore.isAvailable();
    }

    public String encryptTokenBundle(String provider, int userId, String jsonPlaintext) {
        return secretStore.put(purpose(provider), userId, jsonPlaintext);
    }

    public String decryptTokenBundle(String provider, int userId, String reference) {
        return secretStore.get(purpose(provider), userId, reference);
    }

    public void deleteTokenBundleReference(String provider, int userId, String reference) {
        secretStore.delete(purpose(provider), userId, reference);
    }

    private SecretPurpose purpose(String provider) {
        return switch (provider) {
            case ConnectedAccountProviders.GOOGLE -> SecretPurpose.USER_PROVIDER_GOOGLE_TOKEN;
            case ConnectedAccountProviders.MICROSOFT -> SecretPurpose.USER_PROVIDER_MICROSOFT_TOKEN;
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        };
    }
}
