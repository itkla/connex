package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import ooo.klae.connex.backend.validation.ValidPasswordCharacters;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@ToString(exclude = "password")
public class RegisterDto {

    @NotBlank
    @Size(min = 3, max = 64, message = "{register.username.size}")
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "{register.username.pattern}")
    private String username;

    @NotBlank
    @Size(min = 8, max = 72, message = "{register.password.size}")
    @ValidPasswordCharacters
    private String password;

    @NotBlank
    @Size(max = 128)
    private String displayName;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 64)
    private String timezone;

    /** Skips optional personal workspace creation; never grants invitation or membership authority. */
    private boolean skipInitialWorkspace;
}
