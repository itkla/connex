package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A custom field as seen on a record: its definition plus the record's current
 * {@code value} (null when unfilled; its JSON type follows {@code fieldType} —
 * number for {@code number}, boolean for {@code boolean}, else a string).
 */
@Data
@NoArgsConstructor
public class CustomFieldEntryDto {
    private int definitionId;
    private String fieldKey;
    private String label;
    private String fieldType;
    private List<CustomFieldOption> options;
    private boolean required;
    private Object value;
}
