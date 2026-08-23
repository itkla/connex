package ooo.klae.connex.backend.dto;

import java.util.List;
import java.util.Objects;

import ooo.klae.connex.backend.beans.AiChatTurn;

/**
 * Caller-safe durable status for one assistant turn.
 *
 * @param requestedByCurrentUser whether the reader asked for this turn, and therefore whether the
 *     cancellation endpoint would accept a stop request from them. A shared-session participant
 *     watching another member's turn is not its requester and must not be offered that control.
 */
public record AiChatTurnDto(
        int turnId,
        int sessionId,
        String status,
        String terminalReason,
        String partialContent,
        List<AiChatProgressItemDto> progress,
        boolean requestedByCurrentUser) {

    public AiChatTurnDto {
        progress = progress == null ? List.of() : List.copyOf(progress);
    }

    /** Creates a non-streaming turn projection for its own requester. */
    public AiChatTurnDto(int turnId, int sessionId, String status, String terminalReason) {
        this(turnId, sessionId, status, terminalReason, null, List.of(), true);
    }

    /** Creates a streaming turn projection without progress for legacy callers. */
    public AiChatTurnDto(
            int turnId,
            int sessionId,
            String status,
            String terminalReason,
            String partialContent) {
        this(turnId, sessionId, status, terminalReason, partialContent, List.of(), true);
    }

    /**
     * Creates a durable projection that exposes streamed text only to its requester.
     *
     * <p>The partial answer of a turn that failed, was cancelled, or timed out is retained and
     * still returned, so the requester keeps what the turn established; the turn's own terminal
     * status and reason label it as incomplete. A resolved turn returns none, because its complete
     * answer is already the persisted transcript message. Shared viewers never receive streamed
     * text, because a partial has not passed the terminal answer screen a resolved turn applies.
     */
    public static AiChatTurnDto from(
            AiChatTurn turn,
            List<AiChatProgressItemDto> progress,
            Integer viewerUserId) {
        Objects.requireNonNull(turn, "turn");
        boolean requester = Objects.equals(turn.getRequestedByUserId(), viewerUserId);
        return new AiChatTurnDto(
                turn.getId(), turn.getSessionId(), turn.getStatus(), turn.getTerminalReason(),
                turn.isStreamed() && requester && !"resolved".equals(turn.getStatus())
                        ? turn.getPartialContent()
                        : null,
                requester ? progress : sharedProgress(progress),
                requester);
    }

    private static List<AiChatProgressItemDto> sharedProgress(
            List<AiChatProgressItemDto> progress) {
        return progress.stream()
                .map(item -> new AiChatProgressItemDto(
                        item.seq(), item.source(), item.status(), null, false))
                .toList();
    }
}
