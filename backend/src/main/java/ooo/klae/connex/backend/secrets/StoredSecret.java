package ooo.klae.connex.backend.secrets;

import lombok.Data;
import lombok.ToString;

/**
 * Encrypted secret material plus key metadata. The data key is itself encrypted
 * with the configured key-encryption key; plaintext never crosses the mapper
 * boundary.
 */
@Data
@ToString(exclude = { "encryptedDataKey", "ciphertext" })
public class StoredSecret {
    private long id;
    private String scopeType;
    private int scopeId;
    private String purpose;
    private String keyId;
    private String keyAlgorithm;
    private String dataAlgorithm;
    private String encryptedDataKey;
    private String ciphertext;
    private String createdAt;
    private String updatedAt;
    private String rotatedAt;
}
