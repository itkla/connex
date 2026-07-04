package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Payload for confirming an SSO account link: the raw challenge token from the
 * linking redirect and the account's current password, verified once to prove
 * ownership. Both fields are excluded from {@code toString} so they never surface
 * in logs.
 */
@Data
@NoArgsConstructor
@ToString(exclude = {"token", "password"})
public class SsoLinkConfirmRequest {

    @NotBlank
    private String token;

    @NotBlank
    @Size(max = 255)
    private String password;
}
