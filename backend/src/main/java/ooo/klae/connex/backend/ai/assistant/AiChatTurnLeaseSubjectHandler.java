package ooo.klae.connex.backend.ai.assistant;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseKey;
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
    private final AiChatTurnOrphanSettlementService settlementService;

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
     * Settles an abandoned turn from an instance that never owned it.
     *
     * <p>The lease is taken over inside the settlement transaction rather than handed in here,
     * because the fence that stops a revived owner is the turn's own status and that fence closes
     * at the settler's terminal commit, not at the takeover.
     *
     * @param key the turn's lease key
     * @param expectedEpoch the epoch the sweeper observed on the expired lease
     * @return true when this call wrote the turn's terminal state
     */
    @Override
    public boolean settleOrphan(AiRunLeaseKey key, long expectedEpoch) {
        return settlementService.settleOrphan(key, expectedEpoch);
    }
}
