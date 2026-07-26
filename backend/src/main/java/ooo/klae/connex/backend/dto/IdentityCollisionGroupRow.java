package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Restriction-filtered collision group projection returned by MyBatis.
 */
@Data
@NoArgsConstructor
public class IdentityCollisionGroupRow {
    private String recordType;
    private String kind;
    private String normalizedValue;
    private int collisionSize;
    private LocalDateTime rebuiltAt;
}
