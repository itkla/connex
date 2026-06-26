package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single selectable choice for a {@code select} custom field. The {@code key}
 * is the stored value; the {@code label} is what users see.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomFieldOption {

    @NotBlank
    @Size(max = 64)
    private String key;

    @NotBlank
    @Size(max = 128)
    private String label;
}
