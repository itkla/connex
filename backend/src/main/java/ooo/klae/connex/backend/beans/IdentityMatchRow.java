package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One workspace-owned record matched by a current canonical identity.
 */
@Data
@NoArgsConstructor
public class IdentityMatchRow {
    private int recordId;
    private String name;
    private String kind;
    private String normalizedValue;
}
