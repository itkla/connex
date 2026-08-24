package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiBriefSchedule;

/** Tenant-local persistence for per-member Ask Connex brief schedules. */
@Mapper
public interface AiBriefScheduleMapper {

    AiBriefSchedule findForMember(
            @Param("workspaceId") int workspaceId, @Param("userId") int userId);

    /**
     * Replaces one member's declared schedule.
     *
     * <p>The two claim dates are written only when the bean carries them. A null claim date means
     * "leave whatever is stored alone", which is what an ordinary preference save must do; a
     * non-null one is the caller seeding a period so a newly enabled brief cannot fire for a period
     * that is already partly over.
     */
    int upsert(@Param("schedule") AiBriefSchedule schedule);

    /**
     * Lists every schedule in the workspace with at least one enabled period.
     *
     * <p>Due-ness is decided in Java rather than SQL because it depends on each member's declared
     * time zone: the same UTC instant is a different local date and hour for two members, and only
     * the member's own local calendar can say whether their brief is due.
     */
    List<AiBriefSchedule> findEnabled(@Param("workspaceId") int workspaceId);

    /** Lists schedules carrying an in-flight generated brief awaiting delivery. */
    List<AiBriefSchedule> findPendingDelivery(@Param("workspaceId") int workspaceId);

    /**
     * Claims one period for one member, at most once. The compare-and-set on the stored claim date
     * is the whole multi-instance guarantee: only the caller that observes one affected row may
     * start the run.
     */
    int claimPeriod(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("kind") String kind,
            @Param("claimOn") String claimOn);

    /** Records the durable turn the claimed run started, so delivery can find it later. */
    int attachPendingTurn(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("kind") String kind,
            @Param("sessionId") int sessionId,
            @Param("turnId") int turnId,
            @Param("startedAt") String startedAt);

    /**
     * Releases one in-flight brief, at most once, so only one sweep can deliver or drop it.
     *
     * <p>Only a delivered release copies the pending session into {@code last_delivered_session_id}.
     * A dropped one deliberately does not, which leaves a known orphan: a turn released as stalled
     * after two hours, or as generation-failed, may still resolve afterwards, and its session then
     * has no pointer anywhere in the command centre — the member can only reach it by browsing their
     * own Ask Connex session list. Recording it as the last delivered brief was considered and
     * rejected: "Open the last one" would then open a session whose turn failed, and the surface
     * would report a delivery that never happened. Naming stalled sessions honestly needs a separate
     * field, which belongs with the scheduling follow-up rather than here.
     */
    int releasePendingTurn(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("turnId") int turnId,
            @Param("delivered") boolean delivered,
            @Param("failureReason") String failureReason,
            @Param("at") String at);

    /** Records a claimed run that never reached a durable turn, without releasing its claim. */
    int recordStartFailure(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("failureReason") String failureReason,
            @Param("at") String at);

    int deleteForUser(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    int deleteForUserAnywhere(@Param("userId") int userId);
}
