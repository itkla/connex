package ooo.klae.connex.backend.ai;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.secrets.SecretPurpose;
import ooo.klae.connex.backend.secrets.SecretStore;

/**
 * Secret-store facade for per-organization AI provider credentials.
 */
@Component
@RequiredArgsConstructor
public class AiProviderSecretCipher {
    private final SecretStore secretStore;

    public boolean isAvailable() {
        return secretStore.isAvailable();
    }

    public String encryptCredential(int orgId, String jsonPlaintext) {
        return secretStore.put(SecretPurpose.ORG_AI_PROVIDER_CREDENTIAL, orgId, jsonPlaintext);
    }

    public String decryptCredential(int orgId, String reference) {
        return secretStore.get(SecretPurpose.ORG_AI_PROVIDER_CREDENTIAL, orgId, reference);
    }

    public void deleteCredentialReference(int orgId, String reference) {
        secretStore.delete(SecretPurpose.ORG_AI_PROVIDER_CREDENTIAL, orgId, reference);
    }
}
