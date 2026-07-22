package ooo.klae.connex.backend.dto;

import java.time.DayOfWeek;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Full replacement payload for a user's global notification quiet hours.
 * @param enabled whether quiet hours suppress disruptive delivery
 * @param timezone IANA timezone used for the recurring local window
 * @param start local inclusive start in HH:mm form
 * @param end local exclusive end in HH:mm form
 * @param days local days on which the interval starts; an empty disabled value canonicalizes to all days
 */
public record NotificationQuietHoursRequest(
        @NotNull Boolean enabled,
        @NotBlank @Size(max = 64) String timezone,
        @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String start,
        @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String end,
        @NotNull @Size(max = 7) List<@NotNull DayOfWeek> days) {
}
