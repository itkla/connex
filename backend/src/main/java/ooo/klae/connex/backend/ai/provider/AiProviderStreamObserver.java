package ooo.klae.connex.backend.ai.provider;

/** Synchronous normalized provider-stream callbacks on the generation worker thread. */
public interface AiProviderStreamObserver {
    /** Supplies the exact provider reasoning protocol before any content fragment is emitted. */
    default void onReasoningMode(AiReasoningMode reasoningMode) {
    }

    /** Registers an idempotent transport-abort action for prompt cross-thread cancellation. */
    default void onTransportOpen(Runnable cancellation) {
    }

    /** Clears the current transport-abort action. */
    default void onTransportClosed() {
    }

    /** Runs after one complete network event is decoded and may abort the blocking exchange. */
    default void onNetworkChunk() {
    }

    /**
     * Supplies only new text from a non-public field, identified stably within this response.
     * Public content goes exclusively through {@link #onContentDelta}; repeated whole native
     * fields supply only their newly accumulated suffix, never a cumulative snapshot.
     */
    default void onProviderField(String field, String delta) {
    }

    /** Receives one provider-normalized model content fragment. */
    void onContentDelta(String text);
}
