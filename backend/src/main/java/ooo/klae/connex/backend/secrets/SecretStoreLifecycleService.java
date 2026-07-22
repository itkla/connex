package ooo.klae.connex.backend.secrets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.SecretStoreDiagnosticsDto;
import ooo.klae.connex.backend.dto.SecretStoreKeyDiagnosticDto;
import ooo.klae.connex.backend.dto.SecretStoreSecretDiagnosticDto;
import ooo.klae.connex.backend.mappers.SecretValueMapper;
import ooo.klae.connex.backend.services.AuditService;

/**
 * Builds key-lifecycle diagnostics for encrypted integration secrets without
 * exposing plaintext, ciphertext, or wrapped data keys.
 */
@Service
@RequiredArgsConstructor
public class SecretStoreLifecycleService {
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_CONFIGURED = "configured";
    private static final String STATUS_DISABLED = "disabled";
    private static final String STATUS_MISSING = "missing";
    private static final String STATUS_UNSUPPORTED_ALGORITHM = "unsupported_algorithm";
    private static final String STATUS_MISMATCHED_KEY = "mismatched_key";

    private final SecretValueMapper secretValueMapper;
    private final SecretStoreCrypto crypto;
    private final SecretStoreProperties properties;
    private final AuditService auditService;

    public SecretStoreDiagnosticsDto diagnostics() {
        return diagnostics(null, null, true);
    }

    public SecretStoreDiagnosticsDto diagnosticsForWorkspace(int workspaceId) {
        SecretStoreDiagnosticsDto diagnostics = diagnostics("workspace", workspaceId, false);
        auditDiagnostics("workspace", workspaceId, workspaceId, null, diagnostics);
        return diagnostics;
    }

    public SecretStoreDiagnosticsDto diagnosticsForOrg(int orgId) {
        SecretStoreDiagnosticsDto diagnostics = diagnostics("organization", orgId, false);
        auditDiagnostics("organization", orgId, null, orgId, diagnostics);
        return diagnostics;
    }

    public boolean hasBlockingFailures(SecretStoreDiagnosticsDto diagnostics) {
        return !diagnostics.isAvailable()
                || diagnostics.getMissingKeySecrets() > 0
                || diagnostics.getDisabledKeySecrets() > 0
                || diagnostics.getMismatchedSecrets() > 0
                || diagnostics.getUnsupportedAlgorithmSecrets() > 0;
    }

    private SecretStoreDiagnosticsDto diagnostics(String scopeType, Integer scopeId, boolean includeConfiguredKeyring) {
        List<StoredSecret> secrets = secretValueMapper.listForDiagnostics(scopeType, scopeId);
        Map<String, SecretStoreKeyDiagnosticDto> keyDiagnostics = keyDiagnostics(secrets, includeConfiguredKeyring);
        List<SecretStoreSecretDiagnosticDto> failures = new ArrayList<>();

        SecretStoreDiagnosticsDto diagnostics = new SecretStoreDiagnosticsDto();
        diagnostics.setScopeType(scopeType);
        diagnostics.setScopeId(scopeId);
        diagnostics.setActiveKeyId(crypto.activeKeyId());
        diagnostics.setActiveKeyConfigured(crypto.hasConfiguredKey(crypto.activeKeyId()));
        diagnostics.setActiveKeyDisabled(crypto.isDisabled(crypto.activeKeyId()));
        diagnostics.setAvailable(crypto.isAvailable());
        diagnostics.setTotalSecrets(secrets.size());

        int activeSecrets = 0;
        int staleSecrets = 0;
        int missingKeySecrets = 0;
        int disabledKeySecrets = 0;
        int mismatchedSecrets = 0;
        int unsupportedAlgorithmSecrets = 0;

        for (StoredSecret secret : secrets) {
            SecretStoreKeyDiagnosticDto key = keyDiagnostics.get(normalizeKeyId(secret.getKeyId()));
            if (key != null) {
                key.setSecretCount(key.getSecretCount() + 1);
            }

            boolean supportedAlgorithms = hasSupportedAlgorithms(secret);
            boolean disabledKey = crypto.isDisabled(secret.getKeyId());
            boolean missingKey = isBlank(secret.getKeyId()) || !crypto.hasConfiguredKey(secret.getKeyId());
            boolean mismatchedKey = false;
            if (supportedAlgorithms && !disabledKey && !missingKey) {
                mismatchedKey = !crypto.canUnwrapDataKey(secret.getKeyId(),
                        secret.getEncryptedDataKey(), SecretStore.aad(secret));
            }

            if (!supportedAlgorithms) {
                unsupportedAlgorithmSecrets++;
                if (key != null) {
                    key.setUnsupportedAlgorithmSecretCount(key.getUnsupportedAlgorithmSecretCount() + 1);
                }
            }
            if (missingKey) {
                missingKeySecrets++;
            }
            if (disabledKey) {
                disabledKeySecrets++;
            }
            if (mismatchedKey) {
                mismatchedSecrets++;
                if (key != null) {
                    key.setMismatchedSecretCount(key.getMismatchedSecretCount() + 1);
                }
            }

            String failureStatus = failureStatus(secret, supportedAlgorithms, disabledKey, missingKey, mismatchedKey);
            if (failureStatus != null) {
                failures.add(failure(secret, failureStatus, failureReason(failureStatus)));
            } else if (crypto.isActiveKey(secret.getKeyId())) {
                activeSecrets++;
            } else {
                staleSecrets++;
                if (key != null) {
                    key.setStaleSecretCount(key.getStaleSecretCount() + 1);
                }
            }
        }

        diagnostics.setActiveSecrets(activeSecrets);
        diagnostics.setStaleSecrets(staleSecrets);
        diagnostics.setMissingKeySecrets(missingKeySecrets);
        diagnostics.setDisabledKeySecrets(disabledKeySecrets);
        diagnostics.setMismatchedSecrets(mismatchedSecrets);
        diagnostics.setUnsupportedAlgorithmSecrets(unsupportedAlgorithmSecrets);
        diagnostics.setFailures(failures);
        diagnostics.setKeys(keyDiagnostics.values().stream()
                .sorted(Comparator.comparing(SecretStoreKeyDiagnosticDto::isActive).reversed()
                        .thenComparing(SecretStoreKeyDiagnosticDto::getKeyId))
                .toList());
        diagnostics.setHealthy(!hasBlockingFailures(diagnostics));
        return diagnostics;
    }

    private Map<String, SecretStoreKeyDiagnosticDto> keyDiagnostics(List<StoredSecret> secrets,
            boolean includeConfiguredKeyring) {
        Set<String> keyIds = new LinkedHashSet<>();
        addKeyId(keyIds, crypto.activeKeyId());
        if (includeConfiguredKeyring) {
            crypto.configuredKeyIds().stream().sorted().forEach(keyIds::add);
            crypto.disabledKeyIds().stream().sorted().forEach(keyIds::add);
        }
        if (includeConfiguredKeyring && properties.getMetadata() != null) {
            properties.getMetadata().keySet().stream()
                    .map(SecretStoreLifecycleService::normalizeKeyId)
                    .filter(keyId -> keyId != null)
                    .sorted()
                    .forEach(keyIds::add);
        }
        secrets.stream()
                .map(StoredSecret::getKeyId)
                .map(SecretStoreLifecycleService::normalizeKeyId)
                .filter(keyId -> keyId != null)
                .sorted()
                .forEach(keyIds::add);

        Map<String, SecretStoreKeyDiagnosticDto> diagnostics = new LinkedHashMap<>();
        for (String keyId : keyIds) {
            diagnostics.put(keyId, keyDiagnostic(keyId));
        }
        return diagnostics;
    }

    private void auditDiagnostics(String entityType, int entityId, Integer workspaceId, Integer orgId,
            SecretStoreDiagnosticsDto diagnostics) {
        auditService.recordScoped("secret_store.diagnostics.read", entityType, entityId, workspaceId, orgId,
                "secret_store", "Secret store diagnostics read", Map.of(
                        "healthy", diagnostics.isHealthy(),
                        "available", diagnostics.isAvailable(),
                        "totalSecrets", diagnostics.getTotalSecrets(),
                        "staleSecrets", diagnostics.getStaleSecrets(),
                        "missingKeySecrets", diagnostics.getMissingKeySecrets(),
                        "disabledKeySecrets", diagnostics.getDisabledKeySecrets(),
                        "mismatchedSecrets", diagnostics.getMismatchedSecrets(),
                        "unsupportedAlgorithmSecrets", diagnostics.getUnsupportedAlgorithmSecrets()));
    }

    private SecretStoreKeyDiagnosticDto keyDiagnostic(String keyId) {
        SecretStoreProperties.KeyMetadata metadata = metadata(keyId);
        SecretStoreKeyDiagnosticDto dto = new SecretStoreKeyDiagnosticDto();
        dto.setKeyId(keyId);
        dto.setActive(crypto.isActiveKey(keyId));
        dto.setConfigured(crypto.hasConfiguredKey(keyId));
        dto.setDisabled(crypto.isDisabled(keyId));
        dto.setStatus(keyStatus(keyId));
        dto.setAlgorithm(metadata == null ? SecretStoreCrypto.KEY_ALGORITHM : metadata.getAlgorithm());
        dto.setOwner(metadata == null ? "operator" : metadata.getOwner());
        dto.setScope(metadata == null ? "instance" : metadata.getScope());
        if (metadata != null) {
            dto.setVersion(metadata.getVersion());
            dto.setCreatedAt(metadata.getCreatedAt());
            dto.setRotatedAt(metadata.getRotatedAt());
            dto.setDisabledAt(metadata.getDisabledAt());
        }
        return dto;
    }

    private SecretStoreProperties.KeyMetadata metadata(String keyId) {
        if (properties.getMetadata() == null || keyId == null) {
            return null;
        }
        SecretStoreProperties.KeyMetadata metadata = properties.getMetadata().get(keyId);
        if (metadata != null) {
            return metadata;
        }
        return properties.getMetadata().entrySet().stream()
                .filter(entry -> keyId.equals(normalizeKeyId(entry.getKey())))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private String keyStatus(String keyId) {
        if (crypto.isDisabled(keyId)) {
            return STATUS_DISABLED;
        }
        if (crypto.isActiveKey(keyId) && crypto.hasConfiguredKey(keyId)) {
            return STATUS_ACTIVE;
        }
        if (crypto.hasConfiguredKey(keyId)) {
            return STATUS_CONFIGURED;
        }
        return STATUS_MISSING;
    }

    private static boolean hasSupportedAlgorithms(StoredSecret secret) {
        return SecretStoreCrypto.KEY_ALGORITHM.equals(secret.getKeyAlgorithm())
                && SecretStoreCrypto.DATA_ALGORITHM.equals(secret.getDataAlgorithm());
    }

    private static String failureStatus(StoredSecret secret, boolean supportedAlgorithms,
            boolean disabledKey, boolean missingKey, boolean mismatchedKey) {
        if (disabledKey) {
            return STATUS_DISABLED;
        }
        if (missingKey) {
            return STATUS_MISSING;
        }
        if (!supportedAlgorithms) {
            return STATUS_UNSUPPORTED_ALGORITHM;
        }
        if (mismatchedKey) {
            return STATUS_MISMATCHED_KEY;
        }
        return null;
    }

    private static String failureReason(String status) {
        return switch (status) {
            case STATUS_DISABLED -> "The stored key id is disabled by deployment configuration";
            case STATUS_MISSING -> "The stored key id is not configured in the keyring";
            case STATUS_UNSUPPORTED_ALGORITHM -> "The stored key or data algorithm is not supported by this build";
            case STATUS_MISMATCHED_KEY -> "The configured key material cannot unwrap this row's data key";
            default -> "The stored secret is unavailable";
        };
    }

    private static SecretStoreSecretDiagnosticDto failure(StoredSecret secret, String status, String reason) {
        SecretStoreSecretDiagnosticDto dto = new SecretStoreSecretDiagnosticDto();
        dto.setSecretId(secret.getId());
        dto.setScopeType(secret.getScopeType());
        dto.setScopeId(secret.getScopeId());
        dto.setPurpose(secret.getPurpose());
        dto.setKeyId(secret.getKeyId());
        dto.setStatus(status);
        dto.setReason(reason);
        return dto;
    }

    private static void addKeyId(Set<String> keyIds, String keyId) {
        String normalized = normalizeKeyId(keyId);
        if (normalized != null) {
            keyIds.add(normalized);
        }
    }

    private static String normalizeKeyId(String keyId) {
        if (keyId == null) {
            return null;
        }
        String trimmed = keyId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
