package ooo.klae.connex.backend.ai.assistant;

import java.util.List;

import ooo.klae.connex.backend.dto.AiChatPageContextDto;

/** Committed durable preparation for one assistant generation task. */
public record AiChatQueuedTurn(
        int workspaceId,
        int userId,
        int sessionId,
        int turnId,
        int userMessageId,
        long restrictionEpoch,
        List<AiChatPageContextDto> pageContext) {

    public AiChatQueuedTurn {
        pageContext = List.copyOf(pageContext);
    }
}
