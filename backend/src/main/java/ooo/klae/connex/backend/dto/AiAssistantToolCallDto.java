package ooo.klae.connex.backend.dto;

import tools.jackson.databind.JsonNode;

/** Public approval, rejection, execution, and undo state for one assistant tool call. */
public record AiAssistantToolCallDto(
        int id,
        String tool,
        String tier,
        String status,
        JsonNode result,
        boolean undoAvailable,
        String undoExpiresAt) {
}
