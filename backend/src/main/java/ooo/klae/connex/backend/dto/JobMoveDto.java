package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A contact who recently changed companies: the "recently moved" feed row. A champion who moves
 * is the warmest possible lead at the new account. Populated directly by a MyBatis projection
 * (columns map to these fields via underscore-to-camel-case).
 */
@Data
@NoArgsConstructor
public class JobMoveDto {
    private int personId;
    private String personName;
    private String personImageUrl;
    private Integer fromCompanyId;
    private String fromCompanyName;
    private Integer toCompanyId;
    private String toCompanyName;
    private String movedAt;
}
