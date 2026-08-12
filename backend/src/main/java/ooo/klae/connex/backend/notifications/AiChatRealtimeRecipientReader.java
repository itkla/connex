package ooo.klae.connex.backend.notifications;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.AiChatMapper;

/** New-transaction tenant read for the current assistant-session fanout audience. */
@Service
@RequiredArgsConstructor
public class AiChatRealtimeRecipientReader {
    private final AiChatMapper chatMapper;

    /** Returns the owner and currently joined participants eligible for the next frame. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Integer> recipients(int workspaceId, int sessionId) {
        return chatMapper.listRealtimeRecipientUserIds(workspaceId, sessionId);
    }
}
