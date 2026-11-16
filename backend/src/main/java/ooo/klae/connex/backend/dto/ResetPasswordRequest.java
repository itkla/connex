package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ooo.klae.connex.backend.validation.ValidPasswordCharacters;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@ToString(exclude = "newPassword")
public class ResetPasswordRequest {

    @NotBlank
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    @ValidPasswordCharacters
    private String newPassword;
}
