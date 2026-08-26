package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AiBriefSchedule;
import ooo.klae.connex.backend.mappers.AiBriefScheduleMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Sweeps every routed workspace for due and deliverable Ask Connex briefs.
 *
 * <p>Due-ness is decided per member, in the member's own declared time zone, because a schedule that
 * says "08:00 daily" means eight in the morning where they are. The sweep itself owns no
 * at-most-once guarantee: that lives in the compare-and-set claim inside
 * {@link AiBriefRunService}, so two instances sweeping the same workspace at the same moment produce
 * exactly one run.
 *
 * <p>Delivery is swept before starting, so a brief that resolved between passes is announced at the
 * first opportunity rather than waiting behind the next start.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.ai.briefs",
    name = "scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AiBriefScheduler {
    private static final Logger log = LoggerFactory.getLogger(AiBriefScheduler.class);

    private final AiBriefScheduleMapper scheduleMapper;
    private final AiBriefRunService runService;
    private final WorkspaceMapper workspaceMapper;
    private final TenantWorkScope tenantWorkScope;
    private final JobRunRecorder jobRunRecorder;
    private final Clock clock;

    /** Runs one sweep across every routed workspace, without a request security context. */
    @Scheduled(
        fixedDelayString = "${connex.ai.briefs.sweep-delay-ms:300000}",
        initialDelayString = "${connex.ai.briefs.initial-delay-ms:300000}")
    public void sweep() {
        for (Integer workspaceId : tenantWorkScope.unrouted(workspaceMapper::findWorkspaceIds)) {
            try {
                tenantWorkScope.inWorkspace(workspaceId, () -> sweepWorkspace(workspaceId));
            } catch (RuntimeException exception) {
                log.warn("Ask Connex brief sweep failed workspace={} exceptionClass={}",
                        workspaceId, exception.getClass().getSimpleName());
            }
        }
    }

    /** Performs one workspace's delivery and start passes. */
    void sweepWorkspace(int workspaceId) {
        JobRunDetail started = JobRunDetail.startedUtc();
        int delivered = 0;
        int startedRuns = 0;
        int failed = 0;
        for (AiBriefSchedule schedule : scheduleMapper.findPendingDelivery(workspaceId)) {
            switch (safeDeliver(schedule)) {
                case DELIVERED -> delivered++;
                case FAILED -> failed++;
                default -> { }
            }
        }
        for (AiBriefSchedule schedule : scheduleMapper.findEnabled(workspaceId)) {
            String kind = dueKind(schedule);
            if (kind == null) {
                continue;
            }
            switch (safeStart(schedule, kind)) {
                case STARTED -> startedRuns++;
                case FAILED -> failed++;
                default -> { }
            }
        }
        jobRunRecorder.record(
                JobRunRecorder.AI_BRIEF_DELIVERY,
                workspaceId,
                failed == 0 ? JobRunStatus.SUCCEEDED : JobRunStatus.FAILED,
                new JobRunDetail(started.startedAt(), Map.of(
                        "startedCount", startedRuns,
                        "deliveredCount", delivered,
                        "failedCount", failed)));
    }

    private AiBriefRunService.Outcome safeDeliver(AiBriefSchedule schedule) {
        try {
            return runService.deliverPending(schedule);
        } catch (RuntimeException exception) {
            log.warn("Ask Connex brief delivery failed workspace={} exceptionClass={}",
                    schedule.getWorkspaceId(), exception.getClass().getSimpleName());
            return AiBriefRunService.Outcome.FAILED;
        }
    }

    private AiBriefRunService.Outcome safeStart(AiBriefSchedule schedule, String kind) {
        try {
            return runService.start(schedule, kind, localDate(schedule));
        } catch (RuntimeException exception) {
            log.warn("Ask Connex brief start failed workspace={} kind={} exceptionClass={}",
                    schedule.getWorkspaceId(), kind, exception.getClass().getSimpleName());
            return AiBriefRunService.Outcome.FAILED;
        }
    }

    /**
     * Which period, if any, is due for one member right now.
     *
     * <p>Daily is preferred over weekly when both fall due in the same sweep, because the daily brief
     * is the one the member expects that morning; the weekly review remains claimable and is picked
     * up by the next sweep. A period whose local date is already claimed is never due again, so a
     * missed sweep is skipped rather than replayed as a backlog of briefs.
     *
     * <p><strong>Known loss, accepted rather than fixed here.</strong> A member holds exactly one
     * pending-brief slot, and {@code claimPeriod} refuses while that slot is occupied. If the daily
     * brief claimed first and its turn is still in flight when the local day ends, the weekly review
     * for that day is lost — it is not carried into the next day, because due-ness is keyed to the
     * weekday. Allowing the weekly to claim alongside a pending daily is not a contained change:
     * the schedule row stores one {@code pending_kind}/{@code pending_session_id}/
     * {@code pending_turn_id} triple under a database check constraint, {@code attachPendingTurn}
     * refuses a second attach, and a second concurrent run would therefore generate a turn no
     * delivery pass could ever find. A second pending slot is the real fix and belongs with the
     * scheduling follow-up. In practice the window is small: delivery is swept before starting and a
     * stalled turn is released after two hours, so the loss needs a daily run that starts within
     * roughly two hours of local midnight.
     *
     * <p><strong>Zone changes.</strong> Due-ness is read in whatever zone the schedule currently
     * declares. A member who moves their schedule's zone backwards past their brief hour on the same
     * local date can skip that date's brief, and one who moves it forwards can bring the next brief
     * an hour or so early; the claim date is never rewritten to compensate. Both are single-period
     * effects of a rare, deliberate act, and correcting them would mean storing a claim instant
     * rather than a claim date, which would cost the at-most-once-per-local-day guarantee the whole
     * design rests on.
     */
    String dueKind(AiBriefSchedule schedule) {
        ZoneId zone = zone(schedule);
        LocalDateTime local = LocalDateTime.ofInstant(clock.instant(), zone);
        LocalDate today = local.toLocalDate();
        if (schedule.isDailyEnabled()
                && local.getHour() >= schedule.getDailyHour()
                && unclaimed(schedule.getLastDailyClaimOn(), today)) {
            return AiBriefRunService.DAILY;
        }
        if (schedule.isWeeklyEnabled()
                && today.getDayOfWeek().getValue() == schedule.getWeeklyWeekday()
                && local.getHour() >= schedule.getWeeklyHour()
                && unclaimed(schedule.getLastWeeklyClaimOn(), today)) {
            return AiBriefRunService.WEEKLY;
        }
        return null;
    }

    private LocalDate localDate(AiBriefSchedule schedule) {
        return LocalDate.ofInstant(clock.instant(), zone(schedule));
    }

    /**
     * A stored zone that no longer resolves falls back to UTC rather than disabling the brief.
     *
     * <p>The zone is validated when it is stored, so this only fires if a zone is retired from the
     * platform's database between then and now. Delivering the brief an hour off is a better outcome
     * than silently never delivering it again.
     */
    private static ZoneId zone(AiBriefSchedule schedule) {
        String declared = schedule.getTimeZone();
        if (declared == null || declared.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(declared.trim());
        } catch (DateTimeException exception) {
            return ZoneOffset.UTC;
        }
    }

    private static boolean unclaimed(String claimedOn, LocalDate today) {
        if (claimedOn == null || claimedOn.isBlank()) {
            return true;
        }
        try {
            return LocalDate.parse(claimedOn.trim().substring(0, 10)).isBefore(today);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
