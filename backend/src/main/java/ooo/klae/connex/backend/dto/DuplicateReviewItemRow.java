package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Mutable MyBatis projection for one visible duplicate review row. */
@Data
@NoArgsConstructor
public class DuplicateReviewItemRow {
    private long id;
    private String itemType;
    private String recordType;
    private String kind;
    private Integer lowRecordId;
    private String lowName;
    private String lowCompanyName;
    private Integer lowOwnerId;
    private boolean lowOwnedByActiveWorkspace;
    private Integer highRecordId;
    private String highName;
    private String highCompanyName;
    private Integer highOwnerId;
    private boolean highOwnedByActiveWorkspace;
    private int collisionSize;
    private LocalDateTime detectedAt;
    private String state;
    private String evidenceFingerprint;
    private LocalDateTime dismissedAt;
    private Integer dismissedByUserId;
}
