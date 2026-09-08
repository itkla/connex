package ooo.klae.connex.backend.services;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;

import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.dto.RuleTrigger;

/** Resolves one strict date trigger to its local calendar day and persisted UTC instant. */
@Service
public class WorkflowDateScheduleService {

    public Schedule resolve(LocalDate sourceDate, RuleTrigger trigger) {
        ZoneId zone = ZoneId.of(trigger.getTimezone());
        LocalDate scheduledDate = sourceDate.plusDays(trigger.getOffsetDays());
        LocalDateTime local = LocalDateTime.of(
            scheduledDate, LocalTime.parse(trigger.getLocalTime()));
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(local);
        LocalDateTime effectiveLocal;
        ZoneOffset offset;
        if (offsets.isEmpty()) {
            ZoneOffsetTransition transition = rules.getTransition(local);
            if (transition == null) {
                throw new IllegalStateException("Workflow date transition is unavailable");
            }
            effectiveLocal = transition.getDateTimeAfter();
            offset = transition.getOffsetAfter();
        } else {
            effectiveLocal = local;
            offset = offsets.getFirst();
        }
        LocalDateTime dueAt = LocalDateTime.ofInstant(
            effectiveLocal.toInstant(offset), ZoneOffset.UTC);
        return new Schedule(sourceDate, scheduledDate, dueAt, zone);
    }

    /** Source period, scheduled local day, UTC due timestamp, and validated region zone. */
    public record Schedule(
        LocalDate sourceDate,
        LocalDate scheduledLocalDate,
        LocalDateTime dueAt,
        ZoneId zone
    ) { }
}
