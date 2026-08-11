package ooo.klae.connex.backend.notifications;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;

/** Defers assistant realtime frames until durable mutations commit, then resolves live recipients. */
@Component
@RequiredArgsConstructor
public class AiChatRealtimeDispatcher {
    private final ObjectProvider<AiChatRealtimePublisher> realtimePublisher;

    /** Publishes to the current session audience after the surrounding transaction commits. */
    public void sessionAfterCommit(
            int workspaceId, int sessionId, AiChatStepFrameDto frame) {
        afterCommit(() -> sessionNow(workspaceId, sessionId, frame));
    }

    /** Publishes to one explicit user after the surrounding transaction commits. */
    public void userAfterCommit(int userId, AiChatStepFrameDto frame) {
        afterCommit(() -> userNow(userId, frame));
    }

    /** Publishes immediately to the session audience authorized at send time. */
    public void sessionNow(int workspaceId, int sessionId, AiChatStepFrameDto frame) {
        try {
            AiChatRealtimePublisher publisher = realtimePublisher.getIfAvailable();
            if (publisher != null) {
                publisher.sendSession(workspaceId, sessionId, frame);
            }
        } catch (RuntimeException exception) {
            return;
        }
    }

    private void userNow(int userId, AiChatStepFrameDto frame) {
        try {
            AiChatRealtimePublisher publisher = realtimePublisher.getIfAvailable();
            if (publisher != null) {
                publisher.sendUser(userId, frame);
            }
        } catch (RuntimeException exception) {
            return;
        }
    }

    private void afterCommit(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            work.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        work.run();
                    }
                });
    }
}
