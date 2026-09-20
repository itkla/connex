package ooo.klae.connex.backend.ai.assistant;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.lease.AiRunLease;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseKey;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseService;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;

/**
 * Settles one assistant turn whose owning instance stopped heartbeating.
 *
 * <p>The turn is settled failed with {@link AiAssistantTerminalReasons#OWNER_LOST} and is never
 * re-queued. No instance can prove the vanished owner made no provider call and committed no
 * automatic write, and a replay would run a nondeterministic model against idempotency keys the
 * original claimed — so the honest outcome is a failure the member can read, not an answer that
 * silently did the work twice.
 *
 * <p>Everything happens in one transaction, and the order is the documented one:
 * {@code ai_chat_session → ai_chat_turn → ai_run_lease}, lease last. Taking the lease over in a
 * transaction of its own would leave a window in which a revived owner still reads the turn as
 * running, settles it itself, and retires this settler's lease on the way out — the fence is the
 * turn's status and it closes only at this transaction's commit. That is the general contract every
 * lease subject owes, not a chat-turn detail, and it is recorded as such in
 * {@code docs/backend/LOCKING.md} under "AI run leases".
 *
 * <p>Deliberately no {@code AiRestrictionEpoch} read fence, mirroring the reader-triggered expiry:
 * settlement removes a capability and writes no model-derived content, and gating it on the fence
 * would leave a workspace under a processing restriction unable to have its stuck turns released
 * at all. The retained partial answer still passes the same special-care screen every other
 * terminal transition applies.
 */
@Service
@RequiredArgsConstructor
public class AiChatTurnOrphanSettlementService {

    private static final String FAILED = "failed";
    private static final String QUEUED = "queued";
    private static final String RUNNING = "running";
    private static final String TERMINAL = "terminal";

    private final AiChatMapper chatMapper;
    private final AiChatTurnPersistenceService persistenceService;
    private final AiRunLeaseService leaseService;
    private final AiChatRealtimeDispatcher realtimeDispatcher;

    /**
     * Takes one expired turn lease over and settles its turn, in a single transaction.
     *
     * <p>A lease whose turn is already terminal, or whose turn no longer exists at all, is retired
     * rather than taken over: the durable state already proves no owner may act on it, and leaving
     * the row held would have every later pass rediscover an already-settled run ahead of
     * genuinely dead owners. Retiring it is safe without a token because this transaction holds
     * the turn's row lock, so no other settler can be mid-settlement on it.
     *
     * <p><strong>The bound on the retained partial answer, stated rather than inferred.</strong>
     * The settled turn keeps whatever partial answer the vanished owner had already committed,
     * subject to the special-care screen — but <em>only</em> that screen. The
     * authorization-withdrawal branch the same screen applies for {@code restrictions_changed} and
     * {@code access_revoked} cannot fire here, because {@code owner_lost} is deliberately not an
     * authorization withdrawal and because a withdrawal that landed after the owner stopped is not
     * observable to a settler at all: {@code AiRestrictionEpoch} is in-JVM with no durable per-turn
     * epoch, so nothing in the database records the epoch the turn was prepared at. A turn whose
     * requester's authority was withdrawn mid-run therefore retains text a live owner's terminal
     * write would have purged. That is strictly better than today's reader-triggered expiry, which
     * purges nothing at all, and it is a real residual rather than a closed case; the durable
     * cross-instance restriction epoch it needs is #941.
     *
     * @param key the turn's lease key
     * @param expectedEpoch the epoch the sweeper observed on the expired lease
     * @return true when this call wrote the turn's terminal state
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public boolean settleOrphan(AiRunLeaseKey key, long expectedEpoch) {
        int workspaceId = key.workspaceId();
        int turnId = Math.toIntExact(key.subjectId());
        Integer sessionId = chatMapper.getTurnSessionId(workspaceId, turnId);
        if (sessionId == null) {
            leaseService.releaseHeldInCurrentTransaction(key);
            return false;
        }
        if (chatMapper.getSessionByIdForMaintenanceUpdate(workspaceId, sessionId) == null) {
            leaseService.releaseHeldInCurrentTransaction(key);
            return false;
        }
        AiChatTurn stored = chatMapper.getTurnByIdForUpdate(workspaceId, sessionId, turnId);
        if (stored == null || isTerminal(stored)) {
            leaseService.releaseHeldInCurrentTransaction(key);
            return false;
        }
        Optional<AiRunLease> takeover = leaseService.takeOverForSettlement(key, expectedEpoch);
        if (takeover.isEmpty()) {
            return false;
        }
        Optional<AiChatDurableTerminal> settled = persistenceService.settleOrphanedTurn(
                stored, FAILED, AiAssistantTerminalReasons.OWNER_LOST);
        if (settled.isEmpty()) {
            return false;
        }
        AiChatDurableTerminal terminal = settled.get();
        realtimeDispatcher.sessionAfterCommit(
                workspaceId,
                sessionId,
                new AiChatStepFrameDto(
                        workspaceId, sessionId, turnId, terminal.offset(),
                        TERMINAL, null, terminal.status(), terminal.reason()));
        return true;
    }

    private static boolean isTerminal(AiChatTurn turn) {
        return !QUEUED.equals(turn.getStatus()) && !RUNNING.equals(turn.getStatus());
    }
}
