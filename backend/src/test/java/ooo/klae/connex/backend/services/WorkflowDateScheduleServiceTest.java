package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.RuleTrigger;

class WorkflowDateScheduleServiceTest {

    private final WorkflowDateScheduleService service = new WorkflowDateScheduleService();

    @Test
    void appliesCalendarOffsetInTheConfiguredRegion() {
        var schedule = service.resolve(
            LocalDate.of(2027, 3, 31), trigger(-30, "09:00", "Pacific/Honolulu"));

        assertEquals(LocalDate.of(2027, 3, 1), schedule.scheduledLocalDate());
        assertEquals(LocalDateTime.of(2027, 3, 1, 19, 0), schedule.dueAt());
    }

    @Test
    void advancesDstGapToItsFirstValidLocalTime() {
        var schedule = service.resolve(
            LocalDate.of(2027, 3, 14), trigger(0, "02:30", "America/New_York"));

        assertEquals(LocalDateTime.of(2027, 3, 14, 7, 0), schedule.dueAt());
    }

    @Test
    void choosesTheEarlierInstantDuringDstOverlap() {
        var schedule = service.resolve(
            LocalDate.of(2027, 11, 7), trigger(0, "01:30", "America/New_York"));

        assertEquals(LocalDateTime.of(2027, 11, 7, 5, 30), schedule.dueAt());
    }

    private static RuleTrigger trigger(int offsetDays, String localTime, String timezone) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("date");
        trigger.setDateField("expectedCloseDate");
        trigger.setOffsetDays(offsetDays);
        trigger.setLocalTime(localTime);
        trigger.setTimezone(timezone);
        trigger.setAllowManualRuns(false);
        return trigger;
    }
}
