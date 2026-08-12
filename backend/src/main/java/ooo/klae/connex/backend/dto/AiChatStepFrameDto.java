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
        String reason,
        Integer toolCallId) {

    /** Backward-compatible frame construction for states without a durable tool call. */
    public AiChatStepFrameDto(
            int workspaceId,
            int sessionId,
            int turnId,
            int seq,
            String kind,
            String tool,
            String status,
            String reason) {
        this(workspaceId, sessionId, turnId, seq, kind, tool, status, reason, null);
    }
}
