package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Explicit, bounded, read-only record-review request; ownership is resolved by the server. */
public record AiAgentRunCreateRequest(
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String clientRequestKey,
        @NotNull @Pattern(regexp = "record_review_v1") String missionType,
        @NotBlank @Size(max = 16000) String goal,
        @NotNull @Valid Scope scope,
        @Valid Limits limits) {
    public AiAgentRunCreateRequest {
        goal = goal == null ? null : goal.strip();
        limits = limits == null ? new Limits(null, null, null) : limits;
    }

    /** Immutable identifiers-only primary-record scope. */
    public record Scope(@NotNull @Pattern(regexp = "record") String kind,
            @NotNull @Pattern(regexp = "person|company|deal") String recordKind,
            @Min(1) int recordId) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown mission scope field");
        }
    }

    /** Smaller caller-selected limits never increase on retry. */
    public record Limits(@Min(1) @Max(8) Integer maxSteps,
            @Min(8192) @Max(131072) Long maxTokens,
            @Min(180) @Max(900) Integer executionLifetimeSeconds) {
        public Limits {
            maxSteps = maxSteps == null ? 8 : maxSteps;
            maxTokens = maxTokens == null ? 131072L : maxTokens;
            executionLifetimeSeconds = executionLifetimeSeconds == null ? 900 : executionLifetimeSeconds;
        }

        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown mission limit field");
        }
    }

    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown mission field");
    }
}
