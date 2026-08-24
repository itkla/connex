package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Viewer-authorized read projection for one assistant write-tool call. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiAssistantToolCallReadDto(
        int id,
        String toolName,
        String tier,
        String status,
        Target target,
        String requestSummary,
        String outcomeSummary,
        Change change,
        List<OutcomeValue> outcomeValues,
        CreatedRecord createdRecord,
        Integer messageId,
        int turnId,
        String undoExpiresAt,
        boolean undoAvailable,
        String createdAt,
        String updatedAt,
        String executedAt) {

    /** Viewer-authorized target identity, with null details when only its kind is safe. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(
            String kind,
            Integer id,
            String label) {
    }

    /**
     * The exact record change one pending proposal would make, and whether it can still be made.
     *
     * <p>Present only for a viewer who requested the proposal and can currently read its target, so
     * a shared-session participant never learns a field value of a record they cannot open. Both
     * values are nullable in their own right: a record with no owner has no current value, and an
     * unassign proposal has no proposed value, so a clearing change is represented rather than
     * flattened into a missing field.
     *
     * <p>A current value the workspace can no longer name — a record owned by someone who has left
     * it — is a third thing again, and says so through {@code currentValueUnresolved} rather than
     * borrowing the empty record's representation. The two read very differently to the member
     * deciding: one record has nobody on it, the other has somebody this workspace cannot show.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Change(
            String field,
            String currentValue,
            boolean currentValueUnresolved,
            String proposedValue,
            String state) {
    }

    /**
     * The record a completed action created, so the card can open the thing it made.
     *
     * <p>Read from the durable inverse the action recorded for itself, which is the only server-side
     * statement of what was created, and carried under the same viewer authorization as the rest of
     * a completed action's detail. Null for an action that changed an existing record rather than
     * creating one, and for one whose creation has since been undone.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CreatedRecord(
            String kind,
            int id) {
    }

    /**
     * One resulting value of a completed action, named by field rather than pre-rendered as a
     * sentence so the reading client states it in the member's own language.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OutcomeValue(
            String field,
            String value) {
    }
}
