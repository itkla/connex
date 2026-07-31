package ooo.klae.connex.backend.connectedaccounts.capture;

import java.time.Duration;

/**
 * Stable provider capture failure classification.
 */
public class ProviderCaptureException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final boolean cursorInvalid;
    private final Duration retryAfter;

    public ProviderCaptureException(String code, boolean retryable, boolean cursorInvalid, String message) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.cursorInvalid = cursorInvalid;
        this.retryAfter = null;
    }

    public ProviderCaptureException(
            String code, boolean retryable, boolean cursorInvalid, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
        this.cursorInvalid = cursorInvalid;
        this.retryAfter = null;
    }

    public ProviderCaptureException(
            String code,
            boolean retryable,
            boolean cursorInvalid,
            Duration retryAfter,
            String message) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.cursorInvalid = cursorInvalid;
        this.retryAfter = retryAfter;
    }

    /** Machine-readable failure code. */
    public String getCode() {
        return code;
    }

    /** Whether a bounded retry is appropriate. */
    public boolean isRetryable() {
        return retryable;
    }

    /** Whether the durable provider cursor must be reset to a bounded full sync. */
    public boolean isCursorInvalid() {
        return cursorInvalid;
    }

    /** Provider-directed minimum delay, when one was supplied. */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
