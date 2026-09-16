package ooo.klae.connex.backend.delivery;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.secrets.SecretPurpose;
import ooo.klae.connex.backend.secrets.SecretStore;

/**
 * Secret-store facade for per-workspace delivery provider secrets. Email and SMS send credentials
 * have distinct purposes, binding the channel into both the storage identity and authenticated
 * encryption. The inbound webhook secret has its own purpose. Plaintext only ever crosses this
 * facade to the immediate provider that needs it;
 * the {@code delivery_provider_config} row stores only the returned opaque references.
 *
 * <p>V207 clears legacy shared-purpose credential references and disables affected configurations,
 * and the control-plane V209 deletes the shared-purpose {@code secret_value} rows they pointed at:
 * the last channel saved may have overwritten the other channel's key, so its ownership cannot be
 * recovered safely. Operators must re-enter each email and SMS API key and re-enable its config.
 * Legacy references are never decrypted through a fallback purpose.
 *
 * <p>Operator impact on rollback: the migrations are forward-only and destructive by design. Rolling
 * back to a binary that still writes one shared purpose cannot restore the deleted rows or the
 * cleared references, so that binary starts from an unconfigured, disabled provider — and rolling
 * forward again requires re-entering both channel keys a second time, because the rolled-back binary
 * writes them back into the shared slot this cipher no longer reads.
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
     * Encrypts a workspace channel's send credential and returns its opaque reference.
     * @param workspaceId the workspace
     * @param channel the delivery channel
     * @param plaintext the raw credential
     * @return the stored secret reference
     */
    public String encryptCredential(int workspaceId, DeliveryChannel channel, String plaintext) {
        return secretStore.put(credentialPurpose(channel), workspaceId, plaintext);
    }

    /**
     * Decrypts a workspace channel's send credential.
     * @param workspaceId the workspace
     * @param channel the delivery channel
     * @param reference the stored secret reference
     * @return the raw credential
     */
    public String decryptCredential(int workspaceId, DeliveryChannel channel, String reference) {
        return secretStore.get(credentialPurpose(channel), workspaceId, reference);
    }

    /**
     * Deletes a workspace channel's stored send credential.
     * @param workspaceId the workspace
     * @param channel the delivery channel
     * @param reference the stored secret reference
     */
    public void deleteCredentialReference(int workspaceId, DeliveryChannel channel, String reference) {
        secretStore.delete(credentialPurpose(channel), workspaceId, reference);
    }

    private static SecretPurpose credentialPurpose(DeliveryChannel channel) {
        if (channel == null) {
            throw new DeliveryProviderException("Delivery channel is required");
        }
        return switch (channel) {
            case EMAIL -> SecretPurpose.WORKSPACE_DELIVERY_PROVIDER_CREDENTIAL_EMAIL;
            case SMS -> SecretPurpose.WORKSPACE_DELIVERY_PROVIDER_CREDENTIAL_SMS;
            default -> throw new DeliveryProviderException("Unsupported credential channel");
        };
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
