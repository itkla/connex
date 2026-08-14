package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * Existing-account and operator proofs for the time-boxed passkey recovery ceremony.
 */
@Data
@ToString(exclude = {"currentPassword", "recoveryToken"})
public class PasskeyRecoveryRequest {
    @Size(max = 1024)
    private String currentPassword;

    @Size(max = 1024)
    private String recoveryToken;
}
