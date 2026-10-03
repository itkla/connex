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

    /**
     * Whether an organization's stored AI provider credential could be decrypted: the row is in this
     * organization's scope and purpose, its algorithms are supported, and its own key is configured
     * and enabled. Nothing is decrypted and no secret use is audited.
     * @param orgId the organization
     * @param reference the stored secret reference
     * @return whether the credential's metadata permits decryption
     */
    public boolean canDecryptCredential(int orgId, String reference) {
        return secretStore.canDecrypt(SecretPurpose.ORG_AI_PROVIDER_CREDENTIAL, orgId, reference);
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
