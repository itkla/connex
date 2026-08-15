package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;
import ooo.klae.connex.backend.secrets.SecretPurpose;
import ooo.klae.connex.backend.secrets.SecretStore;

/** Secret-store facade for a user's active native authorization PKCE verifier. */
@Component
@RequiredArgsConstructor
public class NativeConnectPkceSecretCipher {
    private final SecretStore secretStore;

    public String store(String provider, int userId, String verifier) {
        return secretStore.put(
            purpose(provider), userId, verifier);
    }

    public String read(String provider, int userId, String reference) {
        return secretStore.get(
            purpose(provider), userId, reference);
    }

    public void delete(String provider, int userId, String reference) {
        secretStore.delete(
            purpose(provider), userId, reference);
    }

    private static SecretPurpose purpose(String provider) {
        return switch (provider) {
            case ConnectedAccountProviders.GOOGLE ->
                SecretPurpose.USER_PROVIDER_PKCE_VERIFIER;
            case ConnectedAccountProviders.MICROSOFT ->
                SecretPurpose.USER_PROVIDER_MICROSOFT_PKCE_VERIFIER;
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        };
    }
}
