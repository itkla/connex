package ooo.klae.connex.backend.ai.assistant;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;

/** Validated write proposal with its server-resolved tenant record identity. */
public record AiAssistantPreparedWrite(
        String toolName,
        ToolTier tier,
        String idempotencyKey,
        String targetKind,
        int targetId,
        String argumentsJson) {
}
