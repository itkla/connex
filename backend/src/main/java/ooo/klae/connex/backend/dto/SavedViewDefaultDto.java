package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Wrapper for a caller's current default, which is null when reset or inaccessible. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SavedViewDefaultDto(SavedViewDto view) {
}
