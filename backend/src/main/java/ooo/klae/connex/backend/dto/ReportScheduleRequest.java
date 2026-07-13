package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Create or replace payload for a report delivery schedule.
 * @param cadence weekly, monthly, or quarterly
 * @param recipientUserIds active workspace member ids
 * @param timezone IANA timezone used for delivery timing
 * @param hourOfDay local delivery hour from 0 through 23
 * @param enabled whether delivery is active
 */
public record ReportScheduleRequest(
        @NotBlank @Pattern(regexp = "weekly|monthly|quarterly") String cadence,
        @NotEmpty @Size(max = 100) List<@NotNull @Positive Integer> recipientUserIds,
        @NotBlank @Size(max = 64) String timezone,
        @Min(0) @Max(23) int hourOfDay,
        @NotNull Boolean enabled) {
}
