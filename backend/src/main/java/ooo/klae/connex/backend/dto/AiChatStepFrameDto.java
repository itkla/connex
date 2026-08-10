package ooo.klae.connex.backend.dto;

/** Metadata-only realtime state frame for one assistant turn step. */
public record AiChatStepFrameDto(
        int workspaceId,
        int sessionId,
        int turnId,
        int seq,
        String kind,
        String tool,
        String status,
        String reason) {
}
