package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.AiBriefSchedule;
import ooo.klae.connex.backend.mappers.AiBriefScheduleMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Pins the one thing the sweep itself decides: whether a member's brief is due right now, in their
 * own calendar. Everything else about at-most-once behaviour lives in the durable claim and is
 * covered by {@link AiBriefRunServiceTest}.
 */
class AiBriefSchedulerTest {

    private final AiBriefScheduleMapper scheduleMapper = mock(AiBriefScheduleMapper.class);
    private final AiBriefRunService runService = mock(AiBriefRunService.class);

    /** 2026-08-24 is a Monday; 07:00 UTC is 16:00 in Tokyo and 00:00 in Los Angeles. */
    private static final Instant MONDAY_0700_UTC = Instant.parse("2026-08-24T07:00:00Z");

    private AiBriefScheduler scheduler(Clock clock) {
        return new AiBriefScheduler(
                scheduleMapper,
                runService,
                mock(WorkspaceMapper.class),
                mock(TenantWorkScope.class),
                mock(JobRunRecorder.class),
                clock);
    }

    private static AiBriefSchedule schedule(String zone) {
        AiBriefSchedule schedule = new AiBriefSchedule();
        schedule.setId(4);
        schedule.setWorkspaceId(7);
        schedule.setUserId(11);
        schedule.setTimeZone(zone);
        schedule.setDailyHour(8);
        schedule.setWeeklyHour(8);
        schedule.setWeeklyWeekday(1);
        return schedule;
    }

    @Test
    void aDailyBriefIsDueOnceTheMembersOwnLocalHourHasArrived() {
        AiBriefScheduler scheduler = scheduler(Clock.fixed(MONDAY_0700_UTC, ZoneOffset.UTC));
        AiBriefSchedule tokyo = schedule("Asia/Tokyo");
        tokyo.setDailyEnabled(true);
        assertEquals(AiBriefRunService.DAILY, scheduler.dueKind(tokyo));

        AiBriefSchedule losAngeles = schedule("America/Los_Angeles");
        losAngeles.setDailyEnabled(true);
        assertNull(scheduler.dueKind(losAngeles),
                "Midnight in Los Angeles is before an 08:00 local daily brief");
    }

    @Test
    void aPeriodAlreadyClaimedForTheMembersLocalDateIsNeverDueAgain() {
        AiBriefScheduler scheduler = scheduler(Clock.fixed(MONDAY_0700_UTC, ZoneOffset.UTC));
        AiBriefSchedule tokyo = schedule("Asia/Tokyo");
        tokyo.setDailyEnabled(true);
        tokyo.setLastDailyClaimOn("2026-08-24");
        assertNull(scheduler.dueKind(tokyo));

        tokyo.setLastDailyClaimOn("2026-08-23");
        assertEquals(AiBriefRunService.DAILY, scheduler.dueKind(tokyo),
                "A new local date makes the daily brief due again");
    }

    @Test
    void aMissedWeekIsSkippedRatherThanReplayedAsABacklog() {
        AiBriefScheduler scheduler = scheduler(Clock.fixed(MONDAY_0700_UTC, ZoneOffset.UTC));
        AiBriefSchedule weekly = schedule("Asia/Tokyo");
        weekly.setWeeklyEnabled(true);
        weekly.setLastWeeklyClaimOn("2026-08-03");
        assertEquals(AiBriefRunService.WEEKLY, scheduler.dueKind(weekly),
                "Three missed weeks still produce exactly one run, on the configured weekday");

        weekly.setWeeklyWeekday(3);
        assertNull(scheduler.dueKind(weekly),
                "A weekly review is never claimed on a day other than its configured weekday");
    }

    @Test
    void aDailyAndAWeeklyFallingDueTogetherStartTheDailyFirst() {
        AiBriefScheduler scheduler = scheduler(Clock.fixed(MONDAY_0700_UTC, ZoneOffset.UTC));
        AiBriefSchedule both = schedule("Asia/Tokyo");
        both.setDailyEnabled(true);
        both.setWeeklyEnabled(true);
        assertEquals(AiBriefRunService.DAILY, scheduler.dueKind(both));
    }

    @Test
    void aStoredZoneThatNoLongerResolvesFallsBackToUtcRatherThanSilencingTheBrief() {
        AiBriefScheduler scheduler = scheduler(Clock.fixed(MONDAY_0700_UTC, ZoneOffset.UTC));
        AiBriefSchedule broken = schedule("Mars/Olympus_Mons");
        broken.setDailyEnabled(true);
        assertNull(scheduler.dueKind(broken),
                "07:00 UTC is before an 08:00 brief once the unresolvable zone falls back to UTC");
        broken.setDailyHour(6);
        assertEquals(AiBriefRunService.DAILY, scheduler.dueKind(broken),
                "The brief still runs on the UTC calendar rather than never running again");
    }

    @Test
    void deliveryIsSweptBeforeStartingSoAResolvedBriefIsAnnouncedAtTheFirstOpportunity() {
        AiBriefSchedule pending = schedule("Asia/Tokyo");
        pending.setPendingKind(AiBriefRunService.DAILY);
        pending.setPendingSessionId(3);
        pending.setPendingTurnId(9);
        when(scheduleMapper.findPendingDelivery(7)).thenReturn(List.of(pending));
        when(scheduleMapper.findEnabled(7)).thenReturn(List.of());
        when(runService.deliverPending(any())).thenReturn(AiBriefRunService.Outcome.DELIVERED);

        scheduler(Clock.fixed(MONDAY_0700_UTC, ZoneOffset.UTC)).sweepWorkspace(7);

        verify(runService).deliverPending(pending);
        verify(runService, never()).start(any(), anyString(), any());
    }

    @Test
    void aWorkspaceWithNoDuePeriodStartsNothing() {
        AiBriefSchedule notDue = schedule("America/Los_Angeles");
        notDue.setDailyEnabled(true);
        when(scheduleMapper.findPendingDelivery(anyInt())).thenReturn(List.of());
        when(scheduleMapper.findEnabled(eq(7))).thenReturn(List.of(notDue));

        scheduler(Clock.fixed(MONDAY_0700_UTC, ZoneOffset.UTC)).sweepWorkspace(7);

        verify(runService, never()).start(any(), anyString(), any());
    }
}
