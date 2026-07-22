package ooo.klae.connex.backend.dto;

import lombok.Data;

/**
 * Metadata-only diagnostic for a secret-store key id and the rows that reference it.
 */
@Data
public class SecretStoreKeyDiagnosticDto {
    private String keyId;
    private String status;
    private boolean active;
    private boolean configured;
    private boolean disabled;
    private String version;
    private String algorithm;
    private String owner;
    private String scope;
    private String createdAt;
    private String rotatedAt;
    private String disabledAt;
    private int secretCount;
    private int staleSecretCount;
    private int mismatchedSecretCount;
    private int unsupportedAlgorithmSecretCount;
}
