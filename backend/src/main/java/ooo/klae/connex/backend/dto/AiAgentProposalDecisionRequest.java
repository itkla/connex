package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Explicit selected-item review contract; authority and prepared arguments remain server-owned. */
public record AiAgentProposalDecisionRequest(
        @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedProposalRevision,
        @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Item> items) {
    /** One immutable preparation selected for an independent transaction. */
    public record Item(
            @NotNull @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String actionSlot,
            @NotNull @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String expectedPreparationId,
            @NotNull @Pattern(regexp = "approve|discard") String decision) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown mission decision item field");
        }
    }

    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown mission decision field");
    }
}
