package ooo.klae.connex.backend.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;

/** Validated future endpoint for a Radar snooze. */
public record RadarSnoozeRequestDto(@NotNull Instant until) {
}
