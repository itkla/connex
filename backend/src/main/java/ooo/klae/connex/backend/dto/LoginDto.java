package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Carries credential data between {@code AuthController} and the client.
 * Not persisted. used only for transport.
 */

@Data
@NoArgsConstructor
public class LoginDto {
    private String username;
    private String password;
}
