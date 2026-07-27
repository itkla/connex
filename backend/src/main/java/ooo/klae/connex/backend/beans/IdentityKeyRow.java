package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Existing record-scoped canonical identity key.
 */
@Data
@NoArgsConstructor
public class IdentityKeyRow {
    private int recordId;
    private String kind;
    private String normalizedValue;
}
