package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AiBriefSchedule;
import ooo.klae.connex.backend.dto.AiBriefScheduleDto;
import ooo.klae.connex.backend.dto.AiBriefScheduleRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.AiBriefScheduleMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * The member's own brief schedule: read and replaced, never read or written for anyone else.
 *
 * <p>A schedule is per member per workspace and is addressed only by the resolved session identity.
 * There is deliberately no administrative read here — a brief schedule is a working preference, and
 * exposing whose briefs run when would turn a convenience into an attendance record.
 */
@Service
@RequiredArgsConstructor
public class AiBriefScheduleService {

    private final AiBriefScheduleMapper scheduleMapper;
    private final WorkspaceService workspaceService;
    private final Clock clock;

    /** Returns the calling member's schedule, or the unscheduled default when they have none. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.AI_USE)
    public AiBriefScheduleDto get() {
        return AiBriefScheduleDto.from(current(), workspaceTimeZone());
    }

    /**
     * Replaces the calling member's schedule.
     *
     * <p>Switching a period on seeds that period's claim date to the member's current local date, so
     * the period they are already inside counts as spent. Without it, enabling the daily brief at
     * nine in the morning would immediately satisfy "the hour has passed and today is unclaimed" and
     * the very next sweep would generate a brief — turning a preference change into an unrequested
     * run, and doing it again for the weekly review the same afternoon. The first brief a member
     * gets is therefore the next scheduled one, which is what the control says it does.
     *
     * @param request the complete schedule the member declared
     * @return the stored schedule as the client should now render it
     * @throws BadRequestException when the declared zone is not a real IANA zone
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiBriefScheduleDto replace(AiBriefScheduleRequest request) {
        if (request == null) {
            throw new BadRequestException("Brief schedule request is invalid");
        }
        AiBriefSchedule existing = current();
        AiBriefSchedule schedule = new AiBriefSchedule();
        schedule.setWorkspaceId(workspaceService.getCurrentWorkspaceId());
        schedule.setUserId(workspaceService.getCurrentUserId());
        schedule.setTimeZone(requireZone(request.timeZone()));
        schedule.setDailyEnabled(request.dailyEnabled());
        schedule.setDailyHour(request.dailyHour());
        schedule.setWeeklyEnabled(request.weeklyEnabled());
        schedule.setWeeklyWeekday(request.weeklyWeekday());
        schedule.setWeeklyHour(request.weeklyHour());
        String today = memberToday(schedule.getTimeZone());
        if (request.dailyEnabled() && (existing == null || !existing.isDailyEnabled())) {
            schedule.setLastDailyClaimOn(today);
        }
        if (request.weeklyEnabled() && (existing == null || !existing.isWeeklyEnabled())) {
            schedule.setLastWeeklyClaimOn(today);
        }
        scheduleMapper.upsert(schedule);
        return AiBriefScheduleDto.from(current(), workspaceTimeZone());
    }

    /** @return the calling member's durable schedule, or null when they have none */
    AiBriefSchedule current() {
        return scheduleMapper.findForMember(
                workspaceService.getCurrentWorkspaceId(), workspaceService.getCurrentUserId());
    }

    /**
     * Validates the declared zone against the JDK's own zone database rather than a stored list.
     *
     * <p>An unvalidated zone would be stored and then fail every sweep afterwards, so the brief would
     * silently never run. Rejecting it at the boundary keeps the failure where the member can see it.
     */
    private static String requireZone(String timeZone) {
        try {
            return ZoneId.of(timeZone.trim()).getId();
        } catch (DateTimeException | NullPointerException exception) {
            throw new BadRequestException("Brief schedule time zone is not a known zone");
        }
    }

    /**
     * The member's own current local date, read in the zone they just declared.
     *
     * <p>The zone has already passed {@link #requireZone}, so it resolves; the schedule's own zone is
     * used rather than the workspace calendar because the sweep decides due-ness in that same zone.
     */
    private String memberToday(String timeZone) {
        return LocalDate.now(clock.withZone(ZoneId.of(timeZone))).toString();
    }

    private String workspaceTimeZone() {
        return AiChatScopeCalendar.zone(workspaceService).getId();
    }
}
