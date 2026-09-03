package ooo.klae.connex.backend.dto.sequence;

import java.time.LocalTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create or replace payload for a sequence and its mutable draft steps.
 *
 * @param name sequence name
 * @param purpose optional authoring purpose
 * @param visibility personal or shared visibility
 * @param timezone IANA send-policy timezone
 * @param weekdayMask seven-bit Monday-through-Sunday mask
 * @param sendWindowStart local send-window start
 * @param sendWindowEnd local send-window end
 * @param steps ordered mutable draft steps
 */
public record SequenceRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String purpose,
        @NotBlank @Pattern(regexp = "personal|shared") String visibility,
        @NotBlank @Size(max = 64) String timezone,
        @Min(1) @Max(127) int weekdayMask,
        @NotNull LocalTime sendWindowStart,
        @NotNull LocalTime sendWindowEnd,
        @NotNull @Valid @Size(max = 100) List<SequenceStepRequest> steps) {
}
