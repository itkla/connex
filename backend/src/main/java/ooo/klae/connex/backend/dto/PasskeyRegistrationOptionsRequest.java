package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Optional proof material for issuing passkey registration options.
 */
@Data
public class PasskeyRegistrationOptionsRequest {
    @Size(max = 255)
    private String currentPassword;
}
