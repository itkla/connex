package ooo.klae.connex.backend.ai.assistant;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.lease.AiRunLease;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubject;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubjectHandler;
import ooo.klae.connex.backend.mappers.AiChatMapper;

/**
 * Bridges the subject-agnostic run lease to one Ask Connex assistant turn.
 *
 * <p>This is the only class that knows both a lease and a turn, which is what keeps every lease
 * statement free of any subject table and the documented lock order
 * {@code ai_chat_session → ai_chat_turn → ai_run_lease} true with the lease always last.
 */
@Component
@RequiredArgsConstructor
public class AiChatTurnLeaseSubjectHandler implements AiRunLeaseSubjectHandler {

    private static final String RUNNING = "running";

    private final AiChatMapper chatMapper;

    @Override
    public AiRunLeaseSubject subject() {
        return AiRunLeaseSubject.CHAT_TURN;
    }

    /**
     * Answers whether the owning instance may keep working on this turn.
     *
     * <p>The read takes no row lock, as the contract requires: it runs on every heartbeat tick and
     * a lock here would drag the lease leaf into the chain the claim and terminal paths walk. A
     * turn another instance has cancelled, failed, or resolved reports {@code false} within one
     * tick, which is the cross-instance stop signal a buffered turn has no other way to observe.
     *
     * @param workspaceId tenant key
     * @param subjectId the turn's identifier
     * @return true only while the turn is still running
     */
    @Override
    public boolean isSubjectRunning(int workspaceId, long subjectId) {
        return RUNNING.equals(chatMapper.getTurnStatus(workspaceId, Math.toIntExact(subjectId)));
    }

    /**
     * Refuses orphan settlement, which no caller reaches yet.
     *
     * <p>Settling an abandoned turn from another instance needs the lease sweeper that discovers
     * one, and it arrives with that sweeper. Until then nothing dispatches settlement, so refusing
     * loudly is preferable to a silent no-op that would look like a settled turn.
     *
     * @param workspaceId tenant key
     * @param subjectId the turn's identifier
     * @param takeover the settler's fencing token
     */
    @Override
    public void settleOrphan(int workspaceId, long subjectId, AiRunLease takeover) {
        throw new UnsupportedOperationException(
                "Assistant turn orphan settlement arrives with the run lease sweeper");
    }
}
