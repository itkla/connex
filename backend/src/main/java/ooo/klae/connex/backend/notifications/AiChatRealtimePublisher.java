package ooo.klae.connex.backend.notifications;

import ooo.klae.connex.backend.dto.AiChatStepFrameDto;

/** Delivery boundary for metadata-only per-user assistant step frames. */
public interface AiChatRealtimePublisher {
    /** Sends one frame to the explicitly identified initiating user. */
    void send(int initiatingUserId, AiChatStepFrameDto frame);
}
