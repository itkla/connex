package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Viewer-safe native answer document and its grounded execution summary.
 *
 * <p>Property inclusion is pinned to ALWAYS so the nullable {@code skill} arrives as an explicit
 * null for answers the generic loop produced rather than as an absent key.
 *
 * @param turnId durable turn identifier
 * @param blocks typed answer-document blocks
 * @param coverage bounded coverage and freshness disclosure
 * @param progress viewer-safe milestone trail
 * @param skill declared skill that produced the answer, or null for the generic loop
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiChatAnswerDocumentDto(
        int turnId,
        List<AiChatAnswerBlockDto> blocks,
        AiChatCoverageDto coverage,
        List<AiChatProgressItemDto> progress,
        AiChatSkillDto skill) {

    /** Creates an answer document that no declared skill produced. */
    public AiChatAnswerDocumentDto(
            int turnId,
            List<AiChatAnswerBlockDto> blocks,
            AiChatCoverageDto coverage,
            List<AiChatProgressItemDto> progress) {
        this(turnId, blocks, coverage, progress, null);
    }

    public AiChatAnswerDocumentDto {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        progress = progress == null ? List.of() : List.copyOf(progress);
    }
}
