package ooo.klae.connex.backend.ai.assistant;

import java.util.List;

import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.ai.AiPrivacyMode;

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
        List<Integer> attachmentIds,
        AiPrivacyMode privacyMode,
        boolean streamed,
        AiChatQueryScope scope) {

    /** Creates a queued turn using the legacy masked, buffered behavior. */
    public AiChatQueuedTurn(
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
        this(workspaceId, userId, sessionId, turnId, userMessageId, userMessageSeq,
                restrictionEpoch, includePrivateNotes, pageContext, attachmentIds,
                AiPrivacyMode.MASKED, false, AiChatQueryScope.none());
    }

    /** Creates a queued turn that declares no query scope. */
    public AiChatQueuedTurn(
            int workspaceId,
            int userId,
            int sessionId,
            int turnId,
            int userMessageId,
            int userMessageSeq,
            long restrictionEpoch,
            boolean includePrivateNotes,
            List<AiChatPageContextDto> pageContext,
            List<Integer> attachmentIds,
            AiPrivacyMode privacyMode,
            boolean streamed) {
        this(workspaceId, userId, sessionId, turnId, userMessageId, userMessageSeq,
                restrictionEpoch, includePrivateNotes, pageContext, attachmentIds,
                privacyMode, streamed, AiChatQueryScope.none());
    }

    public AiChatQueuedTurn {
        pageContext = List.copyOf(pageContext);
        attachmentIds = List.copyOf(attachmentIds);
        privacyMode = privacyMode == null ? AiPrivacyMode.MASKED : privacyMode;
        scope = scope == null ? AiChatQueryScope.none() : scope;
    }
}
