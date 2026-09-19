package ooo.klae.connex.backend.ai.lease;

import java.util.Locale;

/**
 * The kinds of durable AI run a lease may fence.
 *
 * <p>{@link #AGENT_RUN} is declared and accepted by the table's CHECK constraint from the start so
 * that a later agent-run subject reuses the lease table, mapper, service, heartbeat, and sweeper by
 * adding one handler implementation rather than a migration.
 */
public enum AiRunLeaseSubject {
    /** One Ask Connex assistant turn. */
    CHAT_TURN("chat_turn"),
    /** One durable agent run, reserved for the agentic mission executor. */
    AGENT_RUN("agent_run");

    private final String wireKey;

    AiRunLeaseSubject(String wireKey) {
        this.wireKey = wireKey;
    }

    /**
     * Returns the stable key persisted in {@code ai_run_lease.subject_kind}.
     *
     * @return the subject kind's wire key
     */
    public String wireKey() {
        return wireKey;
    }

    /**
     * Resolves a persisted subject kind.
     *
     * @param wireKey the value read from {@code ai_run_lease.subject_kind}
     * @return the matching subject kind
     * @throws IllegalArgumentException when no subject kind declares that key
     */
    public static AiRunLeaseSubject fromWireKey(String wireKey) {
        String normalized = wireKey == null ? "" : wireKey.toLowerCase(Locale.ROOT);
        for (AiRunLeaseSubject subject : values()) {
            if (subject.wireKey.equals(normalized)) {
                return subject;
            }
        }
        throw new IllegalArgumentException("Unknown AI run lease subject kind: " + wireKey);
    }
}
