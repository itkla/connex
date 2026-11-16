package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/** Public approval, rejection, execution, and undo state for one assistant tool call. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiAssistantToolCallDto(
        int id,
        String tool,
        String tier,
        String status,
        String reviewState,
        String reviewExpiresAt,
        JsonNode result,
        boolean undoAvailable,
        String undoExpiresAt) {
}
