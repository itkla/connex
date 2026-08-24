package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.beans.AiBriefSchedule;
import ooo.klae.connex.backend.dto.AiBriefScheduleRequest;
import ooo.klae.connex.backend.mappers.AiBriefScheduleMapper;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Pins that switching a brief on is a preference change rather than a request for one right now:
 * the period the member is already inside is seeded as claimed, so the first brief they receive is
 * the next scheduled one.
 */
class AiBriefScheduleServiceTest {

    /** 2026-08-24 is a Monday; 07:00 UTC is 16:00 in Tokyo, so an 08:00 local brief has passed. */
    private static final Instant MONDAY_0700_UTC = Instant.parse("2026-08-24T07:00:00Z");

    private final AiBriefScheduleMapper scheduleMapper = mock(AiBriefScheduleMapper.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);

    private final AiBriefScheduleService service = new AiBriefScheduleService(
            scheduleMapper, workspaceService, Clock.fixed(MONDAY_0700_UTC, ZoneOffset.UTC));

    @BeforeEach
    void installIdentity() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        when(workspaceService.getCurrentAnalyticsTimezone()).thenReturn("Asia/Tokyo");
    }

    private static AiBriefScheduleRequest request(boolean daily, boolean weekly) {
        return new AiBriefScheduleRequest("Asia/Tokyo", daily, 8, weekly, 1, 8);
    }

    private AiBriefSchedule stored(boolean daily, boolean weekly) {
        AiBriefSchedule schedule = new AiBriefSchedule();
        schedule.setId(4);
        schedule.setWorkspaceId(7);
        schedule.setUserId(11);
        schedule.setTimeZone("Asia/Tokyo");
        schedule.setDailyEnabled(daily);
        schedule.setWeeklyEnabled(weekly);
        return schedule;
    }

    private AiBriefSchedule captureUpsert() {
        ArgumentCaptor<AiBriefSchedule> captor = ArgumentCaptor.forClass(AiBriefSchedule.class);
        verify(scheduleMapper).upsert(captor.capture());
        return captor.getValue();
    }

    @Test
    void switchingABriefOnSeedsTheCurrentLocalPeriodSoItCannotFireImmediately() {
        when(scheduleMapper.findForMember(anyInt(), anyInt())).thenReturn(null, stored(true, true));

        service.replace(request(true, true));

        AiBriefSchedule written = captureUpsert();
        org.junit.jupiter.api.Assertions.assertEquals(
                "2026-08-24", written.getLastDailyClaimOn(),
                "Enabling at 16:00 Tokyo must not make the 08:00 brief due the same day");
        org.junit.jupiter.api.Assertions.assertEquals(
                "2026-08-24", written.getLastWeeklyClaimOn());
    }

    @Test
    void changingAnAlreadyEnabledScheduleLeavesItsClaimHistoryAlone() {
        when(scheduleMapper.findForMember(anyInt(), anyInt()))
                .thenReturn(stored(true, true), stored(true, true));

        service.replace(request(true, true));

        AiBriefSchedule written = captureUpsert();
        assertNull(written.getLastDailyClaimOn(),
                "A preference save must not rewrite a claim the sweep may be acting on");
        assertNull(written.getLastWeeklyClaimOn());
    }

    @Test
    void switchingOnlyOnePeriodOnSeedsOnlyThatPeriod() {
        when(scheduleMapper.findForMember(anyInt(), anyInt()))
                .thenReturn(stored(true, false), stored(true, true));

        service.replace(request(true, true));

        AiBriefSchedule written = captureUpsert();
        assertNull(written.getLastDailyClaimOn());
        org.junit.jupiter.api.Assertions.assertEquals(
                "2026-08-24", written.getLastWeeklyClaimOn());
    }
}
