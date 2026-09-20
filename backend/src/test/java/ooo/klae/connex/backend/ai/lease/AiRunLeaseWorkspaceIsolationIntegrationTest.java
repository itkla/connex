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
 * binding in an unrelated subquery would still pass. This is the second layer, and the fixture is
 * built so that a binding which drifted out of a top-level {@code WHERE} fails here: every subject
 * id under test exists in <em>both</em> workspaces, so a statement that resolved its workspace
 * through a subquery on this table would still find the neighbour's row by subject id alone.
 *
 * <p>The two workspaces' rows carry the same owner string, because one JVM mints one
 * {@code AiRunLeaseIdentity}. The discovery and reap passes take no fencing token, so those keys
 * also share an epoch and the workspace predicate is the only thing that can decide the outcome.
 * The fenced statements do take a token, so there the calling workspace's own row is deliberately
 * moved to a later epoch: the token cannot legitimately match it, and every one of renew,
 * tombstone, and settlement takeover must therefore match nothing at all rather than reaching
 * across into the neighbour's row, which must come back byte-identical.
 *
 * <p>The claim path's own takeover is unfenced by design, so it is covered differently: the
 * neighbour's lease is expired <em>before</em> the calling workspace re-claims its tombstoned row.
 * Both rows are then takeable by subject alone, so a takeover whose workspace predicate had drifted
 * would update two rows and the claim would be refused as a conflict instead of succeeding.
 */
class AiRunLeaseWorkspaceIsolationIntegrationTest extends AbstractAiRunLeaseIntegrationTest {

    @Test
    void expiredLeaseDiscoveryPerWorkspaceNeverReturnsANeighboursRow() {
        AiRunLeaseKey mine = key(AiRunLeaseSubject.CHAT_TURN, 4101L);
        AiRunLeaseKey theirs = neighbourKey(AiRunLeaseSubject.CHAT_TURN, 4101L);
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

    /**
     * The unfenced retire matches on the key alone, so it is the one release statement whose
     * tenant binding is not also carried by an owner token. A neighbouring tenant holding the same
     * subject id must come back untouched.
     */
    @Test
    void anUnfencedRetireNeverReachesANeighboursLease() {
        AiRunLeaseKey mine = key(AiRunLeaseSubject.CHAT_TURN, 4105L);
        AiRunLeaseKey theirs = neighbourKey(AiRunLeaseSubject.CHAT_TURN, 4105L);
        AiRunLease held = acquire(mine);
        AiRunLease neighbourHeld = acquire(theirs);
        leaseRegistry.forget(held);
        leaseRegistry.forget(neighbourHeld);

        assertTrue(release(mine));

        assertNull(leaseRow(mine).get("owner"));
        assertNotNull(leaseRow(mine).get("released_at"));
        assertEquals(neighbourHeld.owner(), leaseRow(theirs).get("owner"));
        assertNull(leaseRow(theirs).get("released_at"));
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
        acquire(sameSubjectHere);
        assertTrue(release(sameSubjectHere));
        expire(theirs);
        Map<String, Object> before = leaseRow(theirs);
        AiRunLease here = acquire(sameSubjectHere);
        assertEquals(held.epoch() + 1L, here.epoch());
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
        assertTrue(leaseService.takeOverForSettlement(sameSubjectHere, held.epoch()).isEmpty());

        Map<String, Object> after = leaseRow(theirs);
        assertEquals(held.owner(), after.get("owner"));
        assertEquals(held.epoch(), ((Number) after.get("epoch")).longValue());
        assertNull(after.get("released_at"));
        assertEquals(before.get("heartbeat_at"), after.get("heartbeat_at"));
        assertEquals(before.get("expires_at"), after.get("expires_at"));
        assertEquals(before.get("acquired_at"), after.get("acquired_at"));

        Map<String, Object> ours = leaseRow(sameSubjectHere);
        assertEquals(here.owner(), ours.get("owner"));
        assertEquals(here.epoch(), ((Number) ours.get("epoch")).longValue());
        assertNull(ours.get("released_at"));
        assertEquals(1, leaseCount(workspace.getId()));
    }
}
