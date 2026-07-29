package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nullable collision-group page projection carrying window metadata or an empty-page sentinel.
 */
@Data
@NoArgsConstructor
public class IdentityCollisionGroupPageRow {
    private String recordType;
    private String kind;
    private String normalizedValue;
    private Integer collisionSize;
    private LocalDateTime rebuiltAt;
    private Long total;
    private Long pageOrdinal;
}
