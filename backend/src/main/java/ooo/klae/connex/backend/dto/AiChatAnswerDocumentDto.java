package ooo.klae.connex.backend.dto;

import java.util.List;

/** Viewer-safe native answer document and its grounded execution summary. */
public record AiChatAnswerDocumentDto(
        int turnId,
        List<AiChatAnswerBlockDto> blocks,
        AiChatCoverageDto coverage,
        List<AiChatProgressItemDto> progress) {

    public AiChatAnswerDocumentDto {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        progress = progress == null ? List.of() : List.copyOf(progress);
    }
}
