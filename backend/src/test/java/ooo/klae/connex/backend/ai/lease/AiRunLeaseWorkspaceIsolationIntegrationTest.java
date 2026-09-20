package ooo.klae.connex.backend.ai.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.AiRunLeaseRow;

/**
 * Runtime cross-workspace coverage for every lease statement that could reach more than one row.
 *
 * <p>{@code TenantScopeArchTest} checks that a scoped statement <em>mentions</em>
 * {@code #{workspaceId}}; its own Javadoc calls that a presence-not-placement heuristic, because a
 * binding in an unrelated subquery would still pass. This is the second layer: each key here exists
 * in both workspaces with the same subject id, the same owner string, and the same epoch, so the
 * workspace predicate is the only thing that can decide the outcome. A workspace binding that
 * drifted out of a top-level {@code WHERE} would let one tenant's sweeper read, re-fence, or delete
 * another tenant's lease rows, and it would leave every other test in this package green.
 */
class AiRunLeaseWorkspaceIsolationIntegrationTest extends AbstractAiRunLeaseIntegrationTest {

    @Test
    void expiredLeaseDiscoveryPerWorkspaceNeverReturnsANeighboursRow() {
        AiRunLeaseKey mine = key(AiRunLeaseSubject.CHAT_TURN, 4101L);
        AiRunLeaseKey theirs = neighbourKey(AiRunLeaseSubject.CHAT_TURN, 4102L);
        acquire(mine);
        acquire(theirs);
        expire(mine);
        expire(theirs);

        List<AiRunLeaseRow> expired = leaseMapper.findExpiredLeases(workspace.getId(), 50);

        assertEquals(1, expired.size());
        assertEquals(4101L, expired.get(0).getSubjectId());
        assertEquals(workspace.getId(), expired.get(0).getWorkspaceId());
        assertEquals(
                1,
                leaseMapper.findExpiredLeases(neighbourWorkspace.getId(), 50).size(),
                "The neighbour's own expired lease must still be visible to the neighbour");
    }

    @Test
    void theReapDeletesOnlyTheCallingWorkspacesTombstones() {
        AiRunLeaseKey mine = key(AiRunLeaseSubject.CHAT_TURN, 4103L);
        AiRunLeaseKey theirs = neighbourKey(AiRunLeaseSubject.CHAT_TURN, 4103L);
        acquire(mine);
        acquire(theirs);
        assertTrue(release(mine));
        assertTrue(release(theirs));
        ageTombstone(mine);
        ageTombstone(theirs);

        assertEquals(1, leaseService.reapTombstones(workspace.getId(), 3600, 50));

        assertEquals(0, leaseCount(workspace.getId()));
        assertEquals(1, leaseCount(neighbourWorkspace.getId()));
        assertNotNull(leaseRow(theirs).get("released_at"));
    }

    @Test
    void anOwnerTokenFromOneWorkspaceCannotRenewTombstoneOrTakeOverAnothersLease() {
        AiRunLeaseKey theirs = neighbourKey(AiRunLeaseSubject.CHAT_TURN, 4104L);
        AiRunLease held = acquire(theirs);
        AiRunLeaseKey sameSubjectHere = key(AiRunLeaseSubject.CHAT_TURN, 4104L);
        AiRunLease impersonation = new AiRunLease(sameSubjectHere, held.owner(), held.epoch());

        assertEquals(AiRunLeaseOutcome.LOST, leaseService.renew(impersonation));
        assertEquals(
                0,
                leaseMapper.tombstone(
                        sameSubjectHere.workspaceId(),
                        sameSubjectHere.subject().wireKey(),
                        sameSubjectHere.subjectId(),
                        held.owner(),
                        held.epoch()));
        expire(theirs);
        assertTrue(leaseService.takeOverForSettlement(sameSubjectHere, held.epoch()).isEmpty());

        Map<String, Object> row = leaseRow(theirs);
        assertEquals(held.owner(), row.get("owner"));
        assertEquals(held.epoch(), ((Number) row.get("epoch")).longValue());
        assertNull(row.get("released_at"));
        assertEquals(0, leaseCount(workspace.getId()));
    }
}
