package ooo.klae.connex.backend.secrets;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.SecretUnavailableException;
import ooo.klae.connex.backend.mappers.SecretValueMapper;
import ooo.klae.connex.backend.services.AuditService;

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
    private final SecretStoreProperties properties;
    private final AuditService auditService;

    public boolean isAvailable() {
        return crypto.isAvailable();
    }

    public boolean hasKey(String keyId) {
        return crypto.hasKey(keyId);
    }

    public String activeKeyId() {
        return crypto.activeKeyId();
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

    @Transactional
    public String get(SecretPurpose purpose, int scopeId, String reference) {
        StoredSecret secret = find(purpose, scopeId, reference);
        try {
            requireSupportedAlgorithms(secret);
            String plaintext = crypto.decrypt(secret.getKeyId(), secret.getEncryptedDataKey(), secret.getCiphertext(),
                    aad(secret));
            boolean rewrapped = properties.isLazyRewrapEnabled() && rewrapToActiveKey(secret, plaintext);
            auditUse(secret, rewrapped);
            return plaintext;
        } catch (RuntimeException e) {
            auditUseFailure(secret, e);
            throw e;
        }
    }

    @Transactional
    public int rewrapBatchToActiveKey(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10_000));
        int count = 0;
        for (StoredSecret secret : secretValueMapper.listRewrapCandidates(crypto.activeKeyId(), safeLimit)) {
            try {
                requireSupportedAlgorithms(secret);
                String plaintext = crypto.decrypt(secret.getKeyId(), secret.getEncryptedDataKey(),
                        secret.getCiphertext(), aad(secret));
                if (rewrapToActiveKey(secret, plaintext)) {
                    count++;
                }
            } catch (RuntimeException e) {
                auditRewrapFailure(secret, e);
                throw e;
            }
        }
        return count;
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

    private boolean rewrapToActiveKey(StoredSecret current, String plaintext) {
        if (crypto.isActiveKey(current.getKeyId())) {
            return false;
        }
        StoredSecret rewrapped = new StoredSecret();
        rewrapped.setId(current.getId());
        rewrapped.setScopeType(current.getScopeType());
        rewrapped.setScopeId(current.getScopeId());
        rewrapped.setPurpose(current.getPurpose());
        rewrapped.setKeyId(crypto.activeKeyId());
        rewrapped.setKeyAlgorithm(SecretStoreCrypto.KEY_ALGORITHM);
        rewrapped.setDataAlgorithm(SecretStoreCrypto.DATA_ALGORITHM);
        SecretStoreCrypto.EncryptedSecret encrypted = crypto.encrypt(plaintext, aad(rewrapped));
        rewrapped.setEncryptedDataKey(encrypted.encryptedDataKey());
        rewrapped.setCiphertext(encrypted.ciphertext());
        int updated = secretValueMapper.updateRewrapped(rewrapped, current.getKeyId(),
                current.getEncryptedDataKey(), current.getCiphertext());
        if (updated == 1) {
            auditRewrap(current, rewrapped.getKeyId());
            return true;
        }
        return false;
    }

    private void auditUse(StoredSecret secret, boolean rewrapped) {
        auditService.recordIndependentScoped("secret_store.secret.use", scopeEntityType(secret), scopeEntityId(secret),
                workspaceAuditScope(secret), orgAuditScope(secret), secret.getPurpose(), "Secret used",
                auditMetadata(secret, rewrapped));
    }

    private void auditUseFailure(StoredSecret secret, RuntimeException exception) {
        auditService.recordFailureScoped("secret_store.secret.use_failed", scopeEntityType(secret),
                scopeEntityId(secret), workspaceAuditScope(secret), orgAuditScope(secret), secret.getPurpose(),
                "Secret use failed", exception.getClass().getSimpleName());
    }

    private void auditRewrap(StoredSecret previous, String newKeyId) {
        auditService.recordScoped("secret_store.secret.rewrap", scopeEntityType(previous), scopeEntityId(previous),
                workspaceAuditScope(previous), orgAuditScope(previous), previous.getPurpose(), "Secret rewrapped",
                Map.of("secretId", previous.getId(), "purpose", previous.getPurpose(),
                        "previousKeyId", previous.getKeyId(), "newKeyId", newKeyId));
    }

    private void auditRewrapFailure(StoredSecret secret, RuntimeException exception) {
        auditService.recordFailureScoped("secret_store.secret.rewrap_failed", scopeEntityType(secret),
                scopeEntityId(secret), workspaceAuditScope(secret), orgAuditScope(secret), secret.getPurpose(),
                "Secret rewrap failed", exception.getClass().getSimpleName());
    }

    private static Map<String, Object> auditMetadata(StoredSecret secret, boolean rewrapped) {
        return Map.of("secretId", secret.getId(), "purpose", secret.getPurpose(), "keyId", secret.getKeyId(),
                "rewrapped", rewrapped);
    }

    private static void requireSupportedAlgorithms(StoredSecret secret) {
        if (!SecretStoreCrypto.KEY_ALGORITHM.equals(secret.getKeyAlgorithm())
                || !SecretStoreCrypto.DATA_ALGORITHM.equals(secret.getDataAlgorithm())) {
            throw new SecretUnavailableException("Encrypted integration secret algorithm is not supported");
        }
    }

    private static String scopeEntityType(StoredSecret secret) {
        return "workspace".equals(secret.getScopeType()) ? "workspace"
                : ("organization".equals(secret.getScopeType()) ? "organization" : "system");
    }

    private static Integer scopeEntityId(StoredSecret secret) {
        return "instance".equals(secret.getScopeType()) ? null : secret.getScopeId();
    }

    private static Integer workspaceAuditScope(StoredSecret secret) {
        return "workspace".equals(secret.getScopeType()) ? secret.getScopeId() : null;
    }

    private static Integer orgAuditScope(StoredSecret secret) {
        return "organization".equals(secret.getScopeType()) ? secret.getScopeId() : null;
    }

    static String aad(StoredSecret secret) {
        return secret.getScopeType() + ":" + secret.getScopeId() + ":" + secret.getPurpose()
                + ":" + secret.getKeyId();
    }
}
