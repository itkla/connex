package ooo.klae.connex.backend.sso;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.secrets.SecretPurpose;
import ooo.klae.connex.backend.secrets.SecretReference;
import ooo.klae.connex.backend.secrets.SecretStore;

/**
 * Compatibility facade for SSO secrets. New writes go to the central envelope
 * secret store; legacy AES-GCM blobs remain decryptable through
 * {@code connex.sso.secret-key} so existing deployments do not lose access.
 */
@Component
public class SsoSecretCipher {

    private final SecretKeySpec key;
    private final SecretStore secretStore;

    public SsoSecretCipher(SsoProperties properties, SecretStore secretStore) {
        this.key = AesGcm.buildKey(properties.getSecretKey());
        this.secretStore = secretStore;
    }

    /**
     * Whether a usable secret-store or legacy encryption key is configured.
     * @return true when a key is available
     */
    public boolean isAvailable() {
        return secretStore.isAvailable() || key != null;
    }

    public boolean hasLegacyKey() {
        return key != null;
    }

    /**
     * Stores an OIDC client secret for an organization.
     * @param orgId the owning organization
     * @param plaintext the value to protect
     * @return the central secret-store reference
     */
    public String encryptOidcClientSecret(int orgId, String plaintext) {
        return secretStore.put(SecretPurpose.ORG_SSO_OIDC_CLIENT_SECRET, orgId, plaintext);
    }

    /**
     * Stores a SAML SP private key for an organization.
     * @param orgId the owning organization
     * @param plaintext the value to protect
     * @return the central secret-store reference
     */
    public String encryptSamlSpPrivateKey(int orgId, String plaintext) {
        return secretStore.put(SecretPurpose.ORG_SSO_SAML_SP_PRIVATE_KEY, orgId, plaintext);
    }

    /**
     * Decrypts a secret-store reference or a legacy Base64 AES-GCM blob.
     * @param blob the stored secret reference or legacy encrypted blob
     * @return the recovered UTF-8 plaintext
     */
    public String decryptOidcClientSecret(int orgId, String blob) {
        if (SecretReference.isReference(blob)) {
            return secretStore.get(SecretPurpose.ORG_SSO_OIDC_CLIENT_SECRET, orgId, blob);
        }
        requireKey();
        return AesGcm.decrypt(key, blob);
    }

    public String decryptSamlSpPrivateKey(int orgId, String blob) {
        if (SecretReference.isReference(blob)) {
            return secretStore.get(SecretPurpose.ORG_SSO_SAML_SP_PRIVATE_KEY, orgId, blob);
        }
        requireKey();
        return AesGcm.decrypt(key, blob);
    }

    public void deleteOidcClientSecretReference(int orgId, String blob) {
        secretStore.delete(SecretPurpose.ORG_SSO_OIDC_CLIENT_SECRET, orgId, blob);
    }

    public void deleteSamlSpPrivateKeyReference(int orgId, String blob) {
        secretStore.delete(SecretPurpose.ORG_SSO_SAML_SP_PRIVATE_KEY, orgId, blob);
    }

    private void requireKey() {
        if (key == null) {
            throw new BadRequestException(
                    "Cannot decrypt a legacy SSO secret: no CONNEX_SSO_SECRET_KEY is configured on this instance");
        }
    }
}
