package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One native answer-document block with viewer-authorized evidence.
 *
 * <p>Property inclusion is pinned to ALWAYS so the application-wide {@code non_null} inclusion
 * cannot drop a null title or body. The browser declares both as required nullable fields, so an
 * omitted key would arrive as undefined and defeat every null comparison made against it.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiChatAnswerBlockDto(
        String kind,
        String title,
        String body,
        List<String> items,
        List<AiChatAnswerRowDto> rows,
        List<AiChatCitationDto> evidence) {

    public AiChatAnswerBlockDto {
        items = items == null ? List.of() : List.copyOf(items);
        rows = rows == null ? List.of() : List.copyOf(rows);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
