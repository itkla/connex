package ooo.klae.connex.backend.dto;

import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for replacing a record's custom-field values: a map of
 * {@code definitionId → value}. A null or empty value clears that field.
 */
@Data
@NoArgsConstructor
public class CustomFieldValuesRequest {
    private Map<Integer, Object> values;
}
