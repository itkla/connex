package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "password")
public class LoginDto {

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Username must contain only letters, numbers, dots, underscores, and hyphens")
    private String username;

    @NotBlank
    @Size(max = 255)
    // @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$", message = "must contain at least one lowercase letter, one uppercase letter, one number, and one special character")
    private String password;
}
