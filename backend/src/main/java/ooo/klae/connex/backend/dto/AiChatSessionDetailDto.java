package ooo.klae.connex.backend.dto;

/** One accessible assistant session and a page of its ordered messages. */
public record AiChatSessionDetailDto(
        AiChatSessionDto session,
        PageResponse<AiChatMessageDto> messages) {
}
