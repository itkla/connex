package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Restriction-filtered collision member projection returned by MyBatis.
 */
@Data
@NoArgsConstructor
public class IdentityCollisionMemberRow {
    private String recordType;
    private String kind;
    private String normalizedValue;
    private int recordId;
    private String recordName;
}
