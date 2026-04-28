package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for user registration request payloads.
 * Used by {@code AuthController} to create a new {@code User}.
 */

@Data
@NoArgsConstructor
public class RegisterDto {
    private String username;
    private String password;
    private String displayName;
    private String email;
}
