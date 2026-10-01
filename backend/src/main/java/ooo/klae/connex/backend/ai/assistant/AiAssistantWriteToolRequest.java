package ooo.klae.connex.backend.ai.assistant;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Closed typed request vocabulary for assistant write tools.
 *
 * <p>Each record is bound to its tool by that tool's {@link AiAssistantWriteTool#requestType()}; a
 * new write tool adds its record here, its bean, and its catalog declaration. The stored request
 * bytes depend on these records' fields and order, so an existing record is never reshaped.
 */
public sealed interface AiAssistantWriteToolRequest {
    String HANDLE = "r[1-9][0-9]*";

    /** Provider-visible handle resolved to a server-side target before persistence. */
    String handle();

    /** Typed activity creation request. */
    record CreateActivity(
            @NotBlank @Pattern(regexp = HANDLE) String handle,
            @NotBlank @Size(max = 32) String type,
            @NotBlank @Size(max = 255) String subject,
            @Size(max = 50_000) String notes,
            @NotBlank @Size(max = 80) String start,
            @JsonProperty("duration_minutes")
            @Min(1) @Max(1_440) Integer durationMinutes)
            implements AiAssistantWriteToolRequest {
    }

    /** Typed task creation request. */
    record CreateTask(
            @NotBlank @Pattern(regexp = HANDLE) String handle,
            @NotBlank @Size(max = 1_000) String description,
            @JsonProperty("due_date")
            @Size(max = 32) String dueDate)
            implements AiAssistantWriteToolRequest {
    }

    /** Typed note creation request. */
    record CreateNote(
            @NotBlank @Pattern(regexp = HANDLE) String handle,
            @NotBlank @Size(max = 50_000) String content,
            @Size(max = 255) String title,
            @Pattern(regexp = "^(private|workspace)$") String visibility)
            implements AiAssistantWriteToolRequest {
    }

    /** Typed existing-tag association request. */
    record AddTag(
            @NotBlank @Pattern(regexp = HANDLE) String handle,
            @NotBlank @Size(max = 64) String tag)
            implements AiAssistantWriteToolRequest {
    }

    /** Typed existing-tag removal proposal. */
    record RemoveTag(
            @NotBlank @Pattern(regexp = HANDLE) String handle,
            @NotBlank @Size(max = 64) String tag)
            implements AiAssistantWriteToolRequest {
    }

    /** Typed deal-stage proposal. */
    record ChangeDealStage(
            @NotBlank @Pattern(regexp = HANDLE) String handle,
            @NotBlank @Size(max = 128) String stage)
            implements AiAssistantWriteToolRequest {
    }

    /** Typed owner-assignment proposal using an exact active-member display name or username. */
    record AssignOwner(
            @NotBlank @Pattern(regexp = HANDLE) String handle,
            @NotBlank @Size(max = 255) String owner)
            implements AiAssistantWriteToolRequest {
    }

    /**
     * Typed first-response deadline proposal, counted in whole hours from the approval.
     *
     * <p>The deadline is a bounded integer rather than a date or time the model transcribes: a
     * member's own date or time with seven or more digits is redacted before the model sees it, and
     * an integer argument can never carry the redaction marker in its place.
     */
    record SetResponseDue(
            @NotBlank @Pattern(regexp = HANDLE) String handle,
            @JsonProperty("due_in_hours")
            @NotNull @Min(1) @Max(8_760) Integer dueInHours)
            implements AiAssistantWriteToolRequest {
    }
}
