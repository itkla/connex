package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UpdateLocaleDto {
    @NotBlank
    @Size(max = 8)
    @Pattern(regexp = "^(en|ja)$", message = "Locale must be en or ja")
    private String locale;
}
