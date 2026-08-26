package ooo.klae.connex.backend.password;

/**
 * Sanitized availability classifications safe for exceptions and audit metadata.
 */
public enum BreachedPasswordUnavailableReason {
    CAPACITY,
    TIMEOUT,
    RATE_LIMITED,
    UPSTREAM,
    MALFORMED_RESPONSE,
    OFFLINE_SOURCE
}
