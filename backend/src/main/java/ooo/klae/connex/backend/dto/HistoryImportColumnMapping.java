package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Maps one source CSV column to one interaction-history field.
 *
 * @param column exact CSV header
 * @param field interaction-history field key
 */
public record HistoryImportColumnMapping(
        @NotBlank @Size(max = 255) String column,
        @NotBlank @Size(max = 64) String field) {
}
