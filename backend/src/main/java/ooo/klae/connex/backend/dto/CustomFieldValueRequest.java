package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for setting a single custom-field value on a record: the raw
 * {@code value}, coerced server-side by the field's type. A null or blank value
 * clears the field.
 */
@Data
@NoArgsConstructor
public class CustomFieldValueRequest {
    private Object value;
}
