package ooo.klae.connex.backend.ai.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The regression that makes {@code epoch} a fence rather than a hint.
 *
 * <p>The failure this guards against is a later agent-run failure, not a chat-turn one: run 77
 * executes segment 1 on instance A as {@code (ownerA, 1)}, segment 1 releases, instance A's stalled
 * heartbeat tick is still queued, and segment 2 is then claimed on the <em>same</em> instance. If
 * release deleted the row, segment 2 would be inserted at epoch 1 again and the stalled tick's
 * {@code WHERE owner = 'A' AND epoch = 1} would match segment 2's row and report that it still held
 * the lease. Releasing by tombstone — owner cleared, row retained — is what prevents that, so the
 * second claim here must land on a strictly greater epoch and the first token must be dead.
 */
class AiRunLeaseEpochMonotonicityIntegrationTest extends AbstractAiRunLeaseIntegrationTest {

    @Test
    void repeatedClaimsByTheSameInstanceStrictlyIncreaseTheEpoch() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.AGENT_RUN, 7701L);

        AiRunLease first = acquire(key);
        assertEquals(1L, first.epoch());
        assertTrue(release(key));

        Map<String, Object> tombstone = leaseRow(key);
        assertNull(tombstone.get("owner"));
        assertNotNull(tombstone.get("released_at"));
        assertEquals(1L, ((Number) tombstone.get("epoch")).longValue());

        AiRunLease second = acquire(key);

        assertEquals(2L, second.epoch());
        assertEquals(first.owner(), second.owner());
        assertEquals(1, leaseCount());
    }

    @Test
    void theFirstClaimsTokenCanNoLongerRenewTombstoneOrTakeOverTheSecondClaim() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.AGENT_RUN, 7702L);
        AiRunLease first = acquire(key);
        assertTrue(release(key));
        AiRunLease second = acquire(key);
        expire(key);

        assertEquals(AiRunLeaseOutcome.LOST, leaseService.renew(first));
        assertEquals(
                0,
                leaseMapper.tombstone(
                        key.workspaceId(),
                        key.subject().wireKey(),
                        key.subjectId(),
                        first.owner(),
                        first.epoch()));
        assertTrue(leaseService.takeOverForSettlement(key, first.epoch()).isEmpty());

        assertEquals(
                second.epoch() + 1L,
                leaseService.takeOverForSettlement(key, second.epoch()).orElseThrow().epoch());
        assertEquals(leaseIdentity.owner(), leaseRow(key).get("owner"));
    }
}
