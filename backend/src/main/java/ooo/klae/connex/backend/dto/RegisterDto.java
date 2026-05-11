package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@ToString(exclude = "password")
public class RegisterDto {

    @NotBlank
    @Size(min = 3, max = 64)
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Username must contain only letters, numbers, dots, underscores, and hyphens")
    private String username;

    @NotBlank
    @Size(min = 8, max = 255)
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$", message = "Password must contain at least one lowercase letter, one uppercase letter, one number, and one special character")
    private String password;

    @NotBlank
    @Size(max = 128)
    private String displayName;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;
}
