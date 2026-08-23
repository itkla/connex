package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One structured row of a metric, comparison, timeline, diff, or extraction block.
 *
 * <p>Property inclusion is pinned to ALWAYS so the application-wide {@code non_null} inclusion
 * cannot drop the value, detail, or instant the server could not establish. The browser declares
 * all three as required nullable fields and renders a placeholder for a null one.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiChatAnswerRowDto(
        String label,
        String value,
        String detail,
        String at,
        List<AiChatCitationDto> evidence) {

    public AiChatAnswerRowDto {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
