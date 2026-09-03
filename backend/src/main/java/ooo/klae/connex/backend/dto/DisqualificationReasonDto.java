package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import ooo.klae.connex.backend.beans.DisqualificationReason;

/** A reason in the workspace's resolved disqualification vocabulary (#559). */
public record DisqualificationReasonDto(
    int id,
    String code,
    String label,
    boolean requiresNote,
    int position,
    boolean builtIn,
    LocalDateTime archivedAt
) {
    /** Projects a persisted reason without exposing its workspace identifier. */
    public static DisqualificationReasonDto from(DisqualificationReason reason) {
        return new DisqualificationReasonDto(
            reason.getId(),
            reason.getCode(),
            reason.getLabel(),
            reason.isRequiresNote(),
            reason.getPosition(),
            reason.isBuiltIn(),
            reason.getArchivedAt());
    }
}
