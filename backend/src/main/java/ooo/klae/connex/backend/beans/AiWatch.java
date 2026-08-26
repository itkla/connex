package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One typed Ask Connex watch over a source-owned CRM condition.
 *
 * <p>A watch never owns a signal. It names a deterministic condition that Radar, tasks, or the deal
 * risk model already computes, plus the threshold and cooldown the member declared, so the trigger
 * can be restated to them verbatim and evaluated without a model ever deciding whether it fired.
 */
@Data
@NoArgsConstructor
public class AiWatch {
    private int id;
    private int workspaceId;
    private int ownerUserId;
    private String watchType;
    private String subjectKind;
    private int subjectId;
    private String thresholdBand;
    private Integer thresholdDays;
    private String thresholdLevel;
    private String status;
    private int cooldownDays;
    private String expiresOn;
    private String lastEvaluatedAt;
    private String lastFiredAt;
    private String lastFiredState;
    private String createdAt;
    private String updatedAt;
}
