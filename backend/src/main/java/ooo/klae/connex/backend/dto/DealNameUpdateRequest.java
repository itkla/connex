package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to change only a deal's name. */
@Data
@NoArgsConstructor
public class DealNameUpdateRequest {
    @NotBlank
    @Size(max = 255)
    private String name;
}
