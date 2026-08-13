package ooo.klae.connex.backend.ai.assistant;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** JVM-local bridge from durable cancellation commits to active blocking stream transports. */
final class AiChatCancellationHooks {
    private static final ConcurrentMap<TurnKey, Runnable> HOOKS = new ConcurrentHashMap<>();

    private AiChatCancellationHooks() {
    }

    static Registration register(AiChatQueuedTurn turn, Runnable cancellation) {
        TurnKey key = new TurnKey(turn.workspaceId(), turn.sessionId(), turn.turnId());
        Runnable action = Objects.requireNonNull(cancellation, "cancellation");
        HOOKS.put(key, action);
        return () -> HOOKS.remove(key, action);
    }

    static void cancel(int workspaceId, int sessionId, int turnId) {
        Runnable action = HOOKS.remove(new TurnKey(workspaceId, sessionId, turnId));
        if (action != null) {
            action.run();
        }
    }

    @FunctionalInterface
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private record TurnKey(int workspaceId, int sessionId, int turnId) {
    }
}
