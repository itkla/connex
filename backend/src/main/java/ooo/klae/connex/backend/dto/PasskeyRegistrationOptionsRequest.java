package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * Optional proof material for issuing passkey registration options. The password is excluded from
 * {@code toString} so it cannot be emitted through diagnostic logging.
 */
@Data
@ToString(exclude = {"currentPassword"})
public class PasskeyRegistrationOptionsRequest {
    @Size(max = 255)
    private String currentPassword;
}
