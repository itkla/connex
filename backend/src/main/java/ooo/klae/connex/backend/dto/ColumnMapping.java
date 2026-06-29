package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Maps one CSV column to a Connex field. {@code field} is a standard field key (e.g. "name",
 * "email") or "custom:{definitionId}" for an existing custom field; when {@code createCustomField}
 * is set, a new custom-field definition of {@code customFieldType} (labelled
 * {@code customFieldLabel}) is created and the column written into it. A null/blank {@code field}
 * with no create flag means the column is ignored.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnMapping {

    @NotBlank
    private String column;

    private String field;

    private boolean createCustomField;

    private String customFieldType;

    private String customFieldLabel;
}
