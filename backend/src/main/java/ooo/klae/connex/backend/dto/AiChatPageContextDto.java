package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Tenant-local record reference supplied by the active page to an assistant turn. */
public record AiChatPageContextDto(
        @NotNull
        @Pattern(regexp = "person|company|deal")
        String kind,
        @Min(1)
        int id) {
}
