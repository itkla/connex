package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Preset, custom-instant, or legacy hour-based notification snooze request.
 */
@Data
@NoArgsConstructor
public class SnoozeRequest {
    @Min(1)
    @Max(720)
    private Integer hours;

    @Size(max = 32)
    private String preset;

    @Size(max = 64)
    private String until;

    @Size(max = 64)
    private String timezone;
}
