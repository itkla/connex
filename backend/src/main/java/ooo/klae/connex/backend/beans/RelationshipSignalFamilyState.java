package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Availability and freshness of one deterministic relationship-signal detector. */
@Data
@NoArgsConstructor
public class RelationshipSignalFamilyState {
    private int workspaceId;
    private String family;
    private String status;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime evidenceAsOf;
    private String errorCode;
}
