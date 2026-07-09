package ooo.klae.connex.backend.secrets;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.SecretValueMapper;

/**
 * Central API for storing and reading never-searched integration secrets. Feature
 * services store only returned references in their own tables; plaintext never
 * leaves this service except to the immediate caller that needs to use it.
 */
@Service
@RequiredArgsConstructor
public class SecretStore {
    private final SecretValueMapper secretValueMapper;
    private final SecretStoreCrypto crypto;

    public boolean isAvailable() {
        return crypto.isAvailable();
    }

    public boolean hasKey(String keyId) {
        return crypto.hasKey(keyId);
    }

    @Transactional
    public String put(SecretPurpose purpose, int scopeId, String plaintext) {
        StoredSecret secret = new StoredSecret();
        secret.setScopeType(purpose.scopeType());
        secret.setScopeId(scopeId);
        secret.setPurpose(purpose.value());
        secret.setKeyId(crypto.activeKeyId());
        secret.setKeyAlgorithm(SecretStoreCrypto.KEY_ALGORITHM);
        secret.setDataAlgorithm(SecretStoreCrypto.DATA_ALGORITHM);
        String aad = aad(secret);
        SecretStoreCrypto.EncryptedSecret encrypted = crypto.encrypt(plaintext, aad);
        secret.setEncryptedDataKey(encrypted.encryptedDataKey());
        secret.setCiphertext(encrypted.ciphertext());
        secretValueMapper.upsert(secret);
        return new SecretReference(secret.getId()).value();
    }

    public String get(SecretPurpose purpose, int scopeId, String reference) {
        StoredSecret secret = find(purpose, scopeId, reference);
        return crypto.decrypt(secret.getKeyId(), secret.getEncryptedDataKey(), secret.getCiphertext(), aad(secret));
    }

    public boolean exists(SecretPurpose purpose, int scopeId, String reference) {
        SecretReference parsed = SecretReference.parseOrNull(reference);
        if (parsed == null) {
            return false;
        }
        StoredSecret secret = secretValueMapper.findById(parsed.id());
        return secret != null && matches(secret, purpose, scopeId);
    }

    @Transactional
    public void delete(SecretPurpose purpose, int scopeId, String reference) {
        SecretReference parsed = SecretReference.parseOrNull(reference);
        if (parsed != null) {
            StoredSecret secret = secretValueMapper.findById(parsed.id());
            if (secret != null && matches(secret, purpose, scopeId)) {
                secretValueMapper.delete(secret.getId());
            }
        }
    }

    private StoredSecret find(SecretPurpose purpose, int scopeId, String reference) {
        SecretReference parsed = SecretReference.parse(reference);
        StoredSecret secret = secretValueMapper.findById(parsed.id());
        if (secret == null) {
            throw new ResourceNotFoundException("Secret reference not found");
        }
        if (!matches(secret, purpose, scopeId)) {
            throw new IllegalStateException("Secret reference scope mismatch");
        }
        return secret;
    }

    private static boolean matches(StoredSecret secret, SecretPurpose purpose, int scopeId) {
        return purpose.scopeType().equals(secret.getScopeType())
                && scopeId == secret.getScopeId()
                && purpose.value().equals(secret.getPurpose());
    }

    private static String aad(StoredSecret secret) {
        return secret.getScopeType() + ":" + secret.getScopeId() + ":" + secret.getPurpose()
                + ":" + secret.getKeyId();
    }
}
