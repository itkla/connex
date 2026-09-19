package ooo.klae.connex.backend.ai;

import java.util.concurrent.CancellationException;

/**
 * Cooperative cancellation checkpoints for AI context assembly and masking. A generation worker
 * cannot be stopped from outside: {@link AiGenerationService} times a generation out by
 * interrupting its worker, and the worker is only released once the running task notices. Loops
 * whose cost grows with CRM content call {@link #throwIfInterrupted()} so an interrupted task
 * unwinds promptly instead of finishing work whose result has already been discarded.
 */
public final class AiCancellation {

    private AiCancellation() {
    }

    /**
     * Throws when the current thread has been interrupted. The interrupt status is deliberately
     * left set, so a checkpoint whose exception an enclosing best-effort helper swallows is observed
     * again by the next checkpoint and by the generation worker itself.
     * @throws CancellationException when the current thread is interrupted
     */
    public static void throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("AI work was cancelled");
        }
    }
}
