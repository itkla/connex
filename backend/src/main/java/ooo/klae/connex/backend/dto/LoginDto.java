package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
    private String username;

    @NotBlank
    @Size(max = 255)
    private String password;
}
