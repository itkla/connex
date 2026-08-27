package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;
import ooo.klae.connex.backend.beans.AiChatMessage;

/** API representation of one assistant chat message. */
@Data
@NoArgsConstructor
public class AiChatMessageDto {
    private int id;
    private int sessionId;
    private int seq;
    private String authorKind;
    private Integer authorUserId;
    private String authorDisplayName;
    private String content;
    private boolean contentWithheld;
    private boolean historySummarized;
    private String createdAt;
    private List<AiChatCitationDto> citations = List.of();
    private List<String> suggestions = List.of();

    /** Maps a persisted message to its API representation. */
    public static AiChatMessageDto from(AiChatMessage message) {
        return from(message, List.of(), List.of(), null);
    }

    /** Maps a persisted message with citations authorized for the current viewer. */
    public static AiChatMessageDto from(
            AiChatMessage message, List<AiChatCitationDto> citations) {
        return from(message, citations, List.of(), null);
    }

    /** Maps a persisted message with viewer-authorized citations and current author identity. */
    public static AiChatMessageDto from(
            AiChatMessage message,
            List<AiChatCitationDto> citations,
            String authorDisplayName) {
        return from(message, citations, List.of(), authorDisplayName);
    }

    /** Maps a persisted message with caller-safe citations and follow-up suggestions. */
    public static AiChatMessageDto from(
            AiChatMessage message,
            List<AiChatCitationDto> citations,
            List<String> suggestions) {
        return from(message, citations, suggestions, null);
    }

    /** Maps a persisted message with viewer-authorized metadata and current author identity. */
    public static AiChatMessageDto from(
            AiChatMessage message,
            List<AiChatCitationDto> citations,
            List<String> suggestions,
            String authorDisplayName) {
        return from(
                message, citations, suggestions, authorDisplayName, false);
    }

    /** Maps a persisted message while withholding content whose live resources are inaccessible. */
    public static AiChatMessageDto from(
            AiChatMessage message,
            List<AiChatCitationDto> citations,
            List<String> suggestions,
            String authorDisplayName,
            boolean contentWithheld) {
        AiChatMessageDto dto = new AiChatMessageDto();
        dto.setId(message.getId());
        dto.setSessionId(message.getSessionId());
        dto.setSeq(message.getSeq());
        dto.setAuthorKind(message.getAuthorKind());
        dto.setAuthorUserId(message.getAuthorUserId());
        dto.setAuthorDisplayName(authorDisplayName);
        boolean historySummarized = "system".equals(message.getAuthorKind());
        dto.setContent(historySummarized || contentWithheld ? "" : message.getContent());
        dto.setContentWithheld(contentWithheld);
        dto.setHistorySummarized(historySummarized);
        dto.setCreatedAt(message.getCreatedAt());
        dto.setCitations(contentWithheld ? List.of() : List.copyOf(citations));
        dto.setSuggestions(contentWithheld ? List.of() : List.copyOf(suggestions));
        return dto;
    }
}
