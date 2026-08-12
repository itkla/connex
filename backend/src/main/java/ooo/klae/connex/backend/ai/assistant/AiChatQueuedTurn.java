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
        int userMessageSeq,
        long restrictionEpoch,
        boolean includePrivateNotes,
        List<AiChatPageContextDto> pageContext,
        List<Integer> attachmentIds) {

    public AiChatQueuedTurn {
        pageContext = List.copyOf(pageContext);
        attachmentIds = List.copyOf(attachmentIds);
    }
}
