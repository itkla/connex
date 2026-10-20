package ooo.klae.connex.backend.notifications;

import ooo.klae.connex.backend.dto.AiChatStepFrameDto;

/** Delivery boundary for metadata-only per-user assistant step frames. */
public interface AiChatRealtimePublisher {
    /** Organization cutoff metadata has no dependency on retained session or participant roots. */
    record PrivacyInvalidated(int workspaceId, String kind, String cutoffGeneration) {}

    /** Withdraws affected cached chats for every currently authorized workspace member. */
    void invalidateWorkspace(int workspaceId, long cutoffGeneration);

    /** Sends one frame to an explicitly identified user. */
    void sendUser(int userId, AiChatStepFrameDto frame);

    /** Sends one frame to the session owner and participants authorized at send time. */
    void sendSession(int workspaceId, int sessionId, AiChatStepFrameDto frame);
}
