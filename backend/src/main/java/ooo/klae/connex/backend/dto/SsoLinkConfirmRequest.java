package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Payload for confirming an SSO account link with the account's current password. The challenge
 * lives in the purpose-bound HttpOnly flow session and the password is excluded from
 * {@code toString}.
 */
@Data
@NoArgsConstructor
@ToString(exclude = "password")
public class SsoLinkConfirmRequest {

    @NotBlank
    @Size(max = 255)
    private String password;
}
