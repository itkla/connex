package ooo.klae.connex.backend.dto;

/** Viewer-authorized read projection for one assistant write-tool call. */
public record AiAssistantToolCallReadDto(
        int id,
        String toolName,
        String tier,
        String status,
        Target target,
        String requestSummary,
        String outcomeSummary,
        Integer messageId,
        int turnId,
        String undoExpiresAt,
        boolean undoAvailable,
        String createdAt,
        String updatedAt,
        String executedAt) {

    /** Viewer-authorized target identity, with null details when only its kind is safe. */
    public record Target(
            String kind,
            Integer id,
            String label) {
    }
}
