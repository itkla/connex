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
     * @param workspaceId tenant key
     * @param subjectId the subject's identifier within the workspace
     * @param takeover the settler's fencing token for the subject's lease
     */
    void settleOrphan(int workspaceId, long subjectId, AiRunLease takeover);
}
