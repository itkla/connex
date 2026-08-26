package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One workspace member's durable Ask Connex brief schedule.
 *
 * <p>The claim dates are the local dates a run was last <em>claimed</em> for, not delivered on.
 * Claiming before generation is what bounds a scheduled brief to at most one attempt per member per
 * local period across every application instance, and what stops a failed brief from being retried
 * until the next period comes round.
 */
@Data
@NoArgsConstructor
public class AiBriefSchedule {
    private int id;
    private int workspaceId;
    private int userId;
    private String timeZone;
    private boolean dailyEnabled;
    private int dailyHour;
    private boolean weeklyEnabled;
    private int weeklyWeekday;
    private int weeklyHour;
    private String lastDailyClaimOn;
    private String lastWeeklyClaimOn;
    private String pendingKind;
    private Integer pendingSessionId;
    private Integer pendingTurnId;
    private String pendingStartedAt;
    private Integer lastDeliveredSessionId;
    private String lastDeliveredKind;
    private String lastDeliveredAt;
    private String lastFailureAt;
    private String lastFailureReason;
    private String createdAt;
    private String updatedAt;
}
