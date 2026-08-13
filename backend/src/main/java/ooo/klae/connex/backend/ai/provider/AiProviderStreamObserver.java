package ooo.klae.connex.backend.ai.provider;

/** Synchronous normalized provider-stream callbacks on the generation worker thread. */
public interface AiProviderStreamObserver {
    /** Registers an idempotent transport-abort action for prompt cross-thread cancellation. */
    default void onTransportOpen(Runnable cancellation) {
    }

    /** Clears the current transport-abort action. */
    default void onTransportClosed() {
    }

    /** Runs after one complete network event is decoded and may abort the blocking exchange. */
    default void onNetworkChunk() {
    }

    /** Receives one provider-normalized model content fragment. */
    void onContentDelta(String text);
}
