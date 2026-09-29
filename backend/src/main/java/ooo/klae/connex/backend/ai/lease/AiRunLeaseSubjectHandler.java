package ooo.klae.connex.backend.ai.lease;

/**
 * The bridge between the subject-agnostic lease mechanism and one kind of leasable run.
 *
 * <p>It exists so that no lease statement ever names a subject table. That keeps the documented
 * lock order {@code ai_chat_session → ai_chat_turn → ai_run_lease} true with the lease always last,
 * and it keeps detection, takeover, and reap reusable: a new subject kind adds one implementation
 * and inherits the mapper, heartbeat, and sweeper unchanged.
 */
public interface AiRunLeaseSubjectHandler {

    /**
     * Returns the subject kind this handler owns.
     *
     * @return the handled subject kind
     */
    AiRunLeaseSubject subject();

    /**
     * Answers whether the subject is still in a state its owner may act on.
     *
     * <p>Implementations must use a non-locking read: this is the heartbeat's cross-instance stop
     * signal and runs on every tick, so taking a row lock here would put the lease leaf into a lock
     * chain it must never join.
     *
     * @param workspaceId tenant key
     * @param subjectId the subject's identifier within the workspace
     * @return {@code true} while the owner may keep working
     */
    boolean isSubjectRunning(int workspaceId, long subjectId);

    /**
     * Settles a subject whose owner stopped heartbeating, in the settler's own transaction.
     *
     * <p>The implementation takes the lease over itself rather than being handed a token, because
     * the fence that stops a revived owner is the subject's own terminal status and that fence
     * closes at the settler's terminal commit, not at the takeover. Implementations must therefore
     * call {@link AiRunLeaseService#takeOverForSettlement(AiRunLeaseKey, long)} and write the
     * subject's terminal state in one transaction, taking the lease last in their lock order. That
     * rule, what a split commit permits, and the required lock order are recorded in
     * {@code docs/backend/LOCKING.md} under "AI run leases", which is the authoritative statement;
     * {@code takeOverForSettlement} declares {@code MANDATORY} propagation so an implementation
     * that forgets it is refused rather than silently committing a standalone takeover.
     *
     * @param key the subject's lease key
     * @param expectedEpoch the epoch the sweeper observed on the expired lease
     * @return {@code true} when this call wrote the subject's terminal state
     */
    boolean settleOrphan(AiRunLeaseKey key, long expectedEpoch);
}
