package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Optimistic state precondition; never an authorization credential. */
public record AiAgentRunControlRequest(
        @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedStateVersion) {
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown mission control field");
    }
}
