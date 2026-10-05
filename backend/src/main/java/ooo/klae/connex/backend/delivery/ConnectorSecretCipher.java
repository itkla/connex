package ooo.klae.connex.backend.delivery;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.secrets.SecretPurpose;
import ooo.klae.connex.backend.secrets.SecretStore;

/**
 * Secret-store facade for per-workspace third-party connector push credentials. Plaintext only ever
 * crosses this facade to the immediate connector that needs it; the {@code connector_config} row
 * stores only the returned opaque reference. Separate from the delivery-provider cipher so a leak or
 * rotation of a connector key never touches a send or webhook secret.
 */
@Component
@RequiredArgsConstructor
public class ConnectorSecretCipher {
    private final SecretStore secretStore;

    /**
     * Whether a workspace's stored connector credential could be decrypted: the row is in this
     * workspace's scope and purpose, its algorithms are supported, and its own key is configured and
     * enabled. Nothing is decrypted and no secret use is audited.
     * @param workspaceId the workspace
     * @param reference the stored secret reference
     * @return whether the credential's metadata permits decryption
     */
    public boolean canDecryptCredential(int workspaceId, String reference) {
        return secretStore.canDecrypt(SecretPurpose.WORKSPACE_CONNECTOR_CREDENTIAL, workspaceId, reference);
    }

    /**
     * Encrypts a workspace's connector push credential and returns its opaque reference.
     * @param workspaceId the workspace
     * @param plaintext the raw credential
     * @return the stored secret reference
     */
    public String encryptCredential(int workspaceId, String plaintext) {
        return secretStore.put(SecretPurpose.WORKSPACE_CONNECTOR_CREDENTIAL, workspaceId, plaintext);
    }

    /**
     * Decrypts a workspace's connector push credential.
     * @param workspaceId the workspace
     * @param reference the stored secret reference
     * @return the raw credential
     */
    public String decryptCredential(int workspaceId, String reference) {
        return secretStore.get(SecretPurpose.WORKSPACE_CONNECTOR_CREDENTIAL, workspaceId, reference);
    }

    /**
     * Deletes a workspace's stored connector push credential.
     * @param workspaceId the workspace
     * @param reference the stored secret reference
     */
    public void deleteCredentialReference(int workspaceId, String reference) {
        secretStore.delete(SecretPurpose.WORKSPACE_CONNECTOR_CREDENTIAL, workspaceId, reference);
    }
}
