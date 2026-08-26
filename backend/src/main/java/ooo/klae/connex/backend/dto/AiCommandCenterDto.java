package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Everything the Ask Connex command centre renders, in one authorized read.
 *
 * <p>It is a view over state other systems own: the member's brief schedule, the session their last
 * delivered brief lives in, and their own watches. Nothing here is a second copy of a signal, a task,
 * or a notification — the watch rows carry their trigger and their last firing so the surface can
 * state the condition plainly, and everything else is reached by link.
 *
 * <p>Inclusion is pinned to ALWAYS so "no brief has ever been delivered" arrives as an explicit null
 * rather than an absent key the browser has to infer.
 *
 * <p>The two sections deliberately answer to different calendars, and the surface says so rather
 * than hiding it. Brief times are local to the zone the member declared on their own schedule, which
 * is what "08:00 daily" has to mean for someone who travels. Watch expiry and evaluation dates are
 * read in the workspace's reporting calendar, the same one that bounds an assistant scope, a report
 * period, or an analytics range — a watch is workspace data, not personal working hours. Unifying
 * them would mean either moving briefs onto the workspace clock, which breaks the promise the
 * schedule control makes, or moving watch dates onto a per-member clock, which would make two
 * members disagree about the day the same watch expired. The honest state is two calendars, each
 * matching the thing it dates.
 *
 * @param schedule the member's brief schedule, never null
 * @param latestBriefSessionId session the last delivered brief lives in, or null
 * @param latestBriefKind period of the last delivered brief, or null
 * @param latestBriefDeliveredAt when the last brief was delivered, or null
 * <p>The two availability flags are separate because the two sections depend on different facts. A
 * brief performs real provider egress, so it needs a configured, usable provider; a watch performs
 * none, so it needs only that the assistant has not been switched off for this member. Reporting one
 * flag for both would either hide working watches from a workspace that has not configured a
 * provider, or promise briefs it cannot generate.
 *
 * @param briefSkillAvailable whether this build and workspace can actually run a brief
 * @param watchesAvailable whether the assistant is switched on for this member, provider aside
 * @param watches the member's watches, newest first
 * @param watchLimit the most watches this member may hold in this workspace
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiCommandCenterDto(
        AiBriefScheduleDto schedule,
        Integer latestBriefSessionId,
        String latestBriefKind,
        String latestBriefDeliveredAt,
        boolean briefSkillAvailable,
        boolean watchesAvailable,
        List<AiWatchDto> watches,
        int watchLimit) {

    public AiCommandCenterDto {
        watches = watches == null ? List.of() : List.copyOf(watches);
    }
}
