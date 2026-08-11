package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;

/** Explicit visibility transition for an owned assistant session. */
public record AiChatSessionShareRequest(@NotNull Boolean shared) {
}
