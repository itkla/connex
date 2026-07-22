package ooo.klae.connex.backend.delivery;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.secrets.SecretPurpose;
import ooo.klae.connex.backend.secrets.SecretStore;

/**
 * Secret-store facade for per-workspace delivery provider secrets. The send credential (an ESP API
 * key) and the inbound webhook secret are two distinct purposes so a leak or rotation of one never
 * exposes the other. Plaintext only ever crosses this facade to the immediate provider that needs it;
 * the {@code delivery_provider_config} row stores only the returned opaque references.
 */
@Component
@RequiredArgsConstructor
public class DeliveryProviderSecretCipher {
    private final SecretStore secretStore;

    /**
     * Whether the underlying secret store is available to encrypt and decrypt.
     * @return true when the secret store can be used
     */
    public boolean isAvailable() {
        return secretStore.isAvailable();
    }

    /**
     * Encrypts a workspace's ESP send credential and returns its opaque reference.
     * @param workspaceId the workspace
     * @param plaintext the raw credential
     * @return the stored secret reference
     */
    public String encryptCredential(int workspaceId, String plaintext) {
        return secretStore.put(SecretPurpose.WORKSPACE_DELIVERY_PROVIDER_CREDENTIAL, workspaceId, plaintext);
    }

    /**
     * Decrypts a workspace's ESP send credential.
     * @param workspaceId the workspace
     * @param reference the stored secret reference
     * @return the raw credential
     */
    public String decryptCredential(int workspaceId, String reference) {
        return secretStore.get(SecretPurpose.WORKSPACE_DELIVERY_PROVIDER_CREDENTIAL, workspaceId, reference);
    }

    /**
     * Deletes a workspace's stored ESP send credential.
     * @param workspaceId the workspace
     * @param reference the stored secret reference
     */
    public void deleteCredentialReference(int workspaceId, String reference) {
        secretStore.delete(SecretPurpose.WORKSPACE_DELIVERY_PROVIDER_CREDENTIAL, workspaceId, reference);
    }

    /**
     * Encrypts a workspace's inbound webhook signing secret and returns its opaque reference.
     * @param workspaceId the workspace
     * @param plaintext the raw webhook secret
     * @return the stored secret reference
     */
    public String encryptWebhookSecret(int workspaceId, String plaintext) {
        return secretStore.put(SecretPurpose.WORKSPACE_DELIVERY_WEBHOOK_SECRET, workspaceId, plaintext);
    }

    /**
     * Decrypts a workspace's inbound webhook signing secret.
     * @param workspaceId the workspace
     * @param reference the stored secret reference
     * @return the raw webhook secret
     */
    public String decryptWebhookSecret(int workspaceId, String reference) {
        return secretStore.get(SecretPurpose.WORKSPACE_DELIVERY_WEBHOOK_SECRET, workspaceId, reference);
    }

    /**
     * Deletes a workspace's stored inbound webhook signing secret.
     * @param workspaceId the workspace
     * @param reference the stored secret reference
     */
    public void deleteWebhookSecretReference(int workspaceId, String reference) {
        secretStore.delete(SecretPurpose.WORKSPACE_DELIVERY_WEBHOOK_SECRET, workspaceId, reference);
    }
}
