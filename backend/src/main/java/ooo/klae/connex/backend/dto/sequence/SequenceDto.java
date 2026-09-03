package ooo.klae.connex.backend.dto.sequence;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * API representation of a workspace-scoped sequence aggregate.
 *
 * @param id sequence id
 * @param name sequence name
 * @param purpose optional purpose
 * @param ownerId optional owner after member offboarding
 * @param visibility personal or shared visibility
 * @param status lifecycle status
 * @param timezone IANA send-policy timezone
 * @param weekdayMask seven-bit weekday mask
 * @param sendWindowStart local window start
 * @param sendWindowEnd local window end
 * @param steps ordered mutable draft steps
 * @param createdAt creation time
 * @param updatedAt update time
 */
public record SequenceDto(
        int id,
        String name,
        String purpose,
        Integer ownerId,
        String visibility,
        String status,
        String timezone,
        int weekdayMask,
        LocalTime sendWindowStart,
        LocalTime sendWindowEnd,
        List<SequenceStepDto> steps,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
