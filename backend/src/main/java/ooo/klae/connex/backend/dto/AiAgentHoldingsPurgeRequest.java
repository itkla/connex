package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Explicitly acknowledged conservative erasure, separate from the human DSR workflow status. */
public record AiAgentHoldingsPurgeRequest(
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String idempotencyKey,
        @NotBlank @Pattern(regexp = "dsr1\\.[A-Za-z0-9_-]{43}") String snapshotToken,
        @NotBlank @Pattern(regexp = "organization_mission_holdings_v1") String scopeAcknowledgement,
        @NotBlank @Pattern(regexp = "contents_at_each_workspace_first_invalidation_v1") String cutoffPolicyAcknowledgement) {
    /** Refuses request fields outside the accepted contract. */
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown mission holdings purge field");
    }
}
