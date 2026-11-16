package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;

/**
 * Requester-only execution evidence without tool arguments, results, or record identifiers.
 *
 * @param toolName durable tool key
 * @param tier authoritative catalog tier, or null for an unknown tool
 * @param status durable execution, proposal, failure, rejection, or undo state
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiChatTurnToolCallDto(String toolName, ToolTier tier, String status) {}
