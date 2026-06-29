package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * How long to snooze a notification, in hours (capped at 30 days).
 */
@Data
@NoArgsConstructor
public class SnoozeRequest {
    @Min(1)
    @Max(720)
    private int hours;
}
