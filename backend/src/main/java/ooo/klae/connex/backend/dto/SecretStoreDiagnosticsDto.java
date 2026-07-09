package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.Data;

/**
 * Metadata-only health report for the central integration-secret store.
 */
@Data
public class SecretStoreDiagnosticsDto {
    private String scopeType;
    private Integer scopeId;
    private String activeKeyId;
    private boolean activeKeyConfigured;
    private boolean activeKeyDisabled;
    private boolean available;
    private boolean healthy;
    private int totalSecrets;
    private int activeSecrets;
    private int staleSecrets;
    private int missingKeySecrets;
    private int disabledKeySecrets;
    private int mismatchedSecrets;
    private int unsupportedAlgorithmSecrets;
    private List<SecretStoreKeyDiagnosticDto> keys = List.of();
    private List<SecretStoreSecretDiagnosticDto> failures = List.of();
}
