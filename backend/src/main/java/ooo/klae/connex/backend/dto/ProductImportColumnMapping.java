package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Maps one source CSV column to one catalog field.
 *
 * @param column exact CSV header
 * @param field catalog field key
 */
public record ProductImportColumnMapping(
        @NotBlank @Size(max = 255) String column,
        @NotBlank @Size(max = 64) String field) {
}
