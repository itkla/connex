package ooo.klae.connex.backend.ai.lease;

import java.util.Locale;

/**
 * The kinds of durable AI run a lease may fence.
 *
 * <p>{@link #AGENT_RUN} is declared and accepted by the table's CHECK constraint from the start so
 * that a later agent-run subject reuses the lease table, mapper, service, heartbeat, and sweeper by
 * adding one handler implementation rather than a migration.
 *
 * <p>Each kind also declares whether its tombstones may be reaped. Deleting a tombstone restarts
 * that key's fencing epoch at 1, so it is only safe for a kind whose runs are provably shorter than
 * the configured tombstone retention — otherwise a revived owner from an earlier claim could match
 * the re-inserted row. {@link #CHAT_TURN} is bounded by {@code connex.ai.generation-max-lifetime},
 * which {@code AiProperties.validateRunLeaseTimings} refuses to let the retention fall below.
 * {@link #AGENT_RUN} has no such declared bound yet, so its tombstones are retained rather than
 * reaped: the reap fails closed on an undeclared bound instead of silently resetting a fence.
 */
public enum AiRunLeaseSubject {
    /** One Ask Connex assistant turn, bounded by the generation lifetime. */
    CHAT_TURN("chat_turn", true),
    /** One durable agent run, reserved for the agentic mission executor. */
    AGENT_RUN("agent_run", false);

    private final String wireKey;
    private final boolean tombstoneReapable;

    AiRunLeaseSubject(String wireKey, boolean tombstoneReapable) {
        this.wireKey = wireKey;
        this.tombstoneReapable = tombstoneReapable;
    }

    /**
     * Answers whether a released lease of this kind may be deleted once the retention window
     * passes.
     *
     * @return {@code true} only when the validated tombstone retention provably exceeds the
     *     longest a run of this kind can last
     */
    public boolean isTombstoneReapable() {
        return tombstoneReapable;
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
