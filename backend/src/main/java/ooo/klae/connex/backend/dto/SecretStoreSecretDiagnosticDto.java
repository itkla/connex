package ooo.klae.connex.backend.dto;

import lombok.Data;

/**
 * Metadata-only diagnostic for an encrypted secret-store row. Ciphertext,
 * encrypted data keys, and plaintext are never exposed.
 */
@Data
public class SecretStoreSecretDiagnosticDto {
    private long secretId;
    private String scopeType;
    private int scopeId;
    private String purpose;
    private String keyId;
    private String status;
    private String reason;
}
