package ooo.klae.connex.backend.dto;

/** One accessible assistant session and a page of its ordered messages. */
public record AiChatSessionDetailDto(
        AiChatSessionDto session,
        PageResponse<AiChatMessageDto> messages,
        AiChatTurnDto activeTurn) {

    /** Creates a detail projection without an active turn for legacy callers. */
    public AiChatSessionDetailDto(
            AiChatSessionDto session,
            PageResponse<AiChatMessageDto> messages) {
        this(session, messages, null);
    }
}
