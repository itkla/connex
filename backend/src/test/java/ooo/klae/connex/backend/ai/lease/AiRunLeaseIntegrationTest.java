package ooo.klae.connex.backend.ai.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;

import ooo.klae.connex.backend.beans.AiRunLeaseRow;

/**
 * Real-database behaviour of the run lease: MySQL owns every deadline, the {@code (owner, epoch)}
 * pair is a fence, the release-state CHECK is enforced by the database, and the catalog-pinned
 * discovery and reap predicates find exactly the rows a sweeper must act on.
 */
class AiRunLeaseIntegrationTest extends AbstractAiRunLeaseIntegrationTest {

    /**
     * Timestamp columns may only ever be assigned from the database clock, from another column of
     * the same row, or cleared. A bound parameter here would put a lease deadline on a JVM clock and
     * let instance skew move it. {@code GREATEST(CURRENT_TIMESTAMP(6), …)} is still the database's
     * own clock: it is how the release keeps the expiry CHECK satisfiable when that clock has
     * stepped backwards since the lease was acquired. {@code GREATEST(DATE_ADD(CURRENT_TIMESTAMP(6),
     * …), …)} is the same guard applied to a renewal's deadline.
     */
    private static final Pattern TIMESTAMP_ASSIGNMENT = Pattern.compile(
            "(acquired_at|heartbeat_at|expires_at|released_at)\\s*=\\s*(?!"
                    + "CURRENT_TIMESTAMP\\(6\\)|DATE_ADD\\(CURRENT_TIMESTAMP\\(6\\)"
                    + "|GREATEST\\(CURRENT_TIMESTAMP\\(6\\)"
                    + "|GREATEST\\(\\s*DATE_ADD\\(CURRENT_TIMESTAMP\\(6\\)|NULL)(\\S+)");

    @Test
    void everyLeaseDeadlineIsComputedByTheDatabase() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 3001L);

        AiRunLease lease = acquire(key);

        Map<String, Object> row = leaseRow(key);
        assertEquals(leaseIdentity.owner(), row.get("owner"));
        assertEquals(1L, lease.epoch());
        assertEquals(45_000_000L, ((Number) row.get("ttl_micros")).longValue());
        assertTrue(
                Math.abs(((Number) row.get("age_seconds")).longValue()) <= 5L,
                "acquired_at must be the database's own clock reading");
        assertNull(row.get("released_at"));
    }

    @Test
    void noTimestampColumnIsAssignedFromABoundParameter() throws IOException {
        String xml = new String(
                new ClassPathResource("mappers/AiRunLeaseMapper.xml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        Matcher matcher = TIMESTAMP_ASSIGNMENT.matcher(xml);
        String offender = matcher.find() ? matcher.group() : "";

        assertTrue(
                offender.isEmpty(),
                "Lease timestamps must come from MySQL, found assignment: " + offender);
    }

    @Test
    void aForeignOwnerCanNeitherRenewNorTombstoneAnotherInstancesLease() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 3002L);
        AiRunLease held = acquire(key);
        AiRunLease impostor = new AiRunLease(key, UUID.randomUUID().toString(), held.epoch());

        assertEquals(AiRunLeaseOutcome.LOST, leaseService.renew(impostor));
        assertEquals(
                0,
                leaseMapper.tombstone(
                        key.workspaceId(),
                        key.subject().wireKey(),
                        key.subjectId(),
                        impostor.owner(),
                        impostor.epoch()));

        Map<String, Object> row = leaseRow(key);
        assertEquals(leaseIdentity.owner(), row.get("owner"));
        assertNull(row.get("released_at"));
        assertEquals(AiRunLeaseOutcome.HELD, leaseService.renew(held));
    }

    @Test
    void theDatabaseRejectsAHalfWrittenRelease() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 3003L);
        acquire(key);

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE ai_run_lease SET owner = NULL"
                        + " WHERE workspace_id = ? AND subject_kind = ? AND subject_id = ?",
                key.workspaceId(), key.subject().wireKey(), key.subjectId()));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE ai_run_lease SET released_at = CURRENT_TIMESTAMP(6)"
                        + " WHERE workspace_id = ? AND subject_kind = ? AND subject_id = ?",
                key.workspaceId(), key.subject().wireKey(), key.subjectId()));

        assertEquals(leaseIdentity.owner(), leaseRow(key).get("owner"));
    }

    @Test
    void releaseTombstonesTheRowAndKeepsItsEpoch() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 3004L);
        AiRunLease held = acquire(key);

        assertTrue(release(key));

        Map<String, Object> row = leaseRow(key);
        assertNull(row.get("owner"));
        assertNotNull(row.get("released_at"));
        assertEquals(held.epoch(), ((Number) row.get("epoch")).longValue());
        assertTrue(leaseRegistry.find(key).isEmpty());
    }

    /**
     * The owner token lives in one JVM's memory, so a terminal write routed to any other instance
     * — a cancel behind a load balancer, a lazy expiry on a turn poll — carries no token. Forgoing
     * the release there would leave the row held for good, because the reap deletes tombstones
     * only. Forgetting the token is a faithful stand-in for a second instance: the token's whole
     * scope is this map.
     */
    @Test
    void aTerminalWriteFromAnInstanceHoldingNoTokenStillRetiresTheHeldRow() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 3021L);
        AiRunLease held = acquire(key);
        leaseRegistry.forget(held);

        assertTrue(release(key));

        Map<String, Object> row = leaseRow(key);
        assertNull(row.get("owner"));
        assertNotNull(row.get("released_at"));
        assertEquals(held.epoch(), ((Number) row.get("epoch")).longValue());
        assertEquals(AiRunLeaseOutcome.LOST, leaseService.renew(held));
    }

    @Test
    void retiringAnAlreadyReleasedRowChangesNothingAndReportsNoRelease() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 3022L);
        AiRunLease held = acquire(key);
        assertTrue(release(key));
        Object releasedAt = leaseRow(key).get("released_at");

        assertFalse(release(key));

        assertEquals(releasedAt, leaseRow(key).get("released_at"));
        assertEquals(held.epoch(), ((Number) leaseRow(key).get("epoch")).longValue());
    }

    @Test
    void expiredLeaseDiscoveryIsWorkspacePagedAndTakeoverIsEpochFenced() {
        AiRunLeaseKey expiredKey = key(AiRunLeaseSubject.CHAT_TURN, 3005L);
        AiRunLeaseKey liveKey = key(AiRunLeaseSubject.AGENT_RUN, 3006L);
        AiRunLease expiredLease = acquire(expiredKey);
        acquire(liveKey);
        expire(expiredKey);

        List<AiRunLeaseRow> expired =
                leaseMapper.findExpiredLeases(workspace.getId(), List.of(CHAT_TURN), 10);
        assertEquals(1, expired.size());
        assertEquals(3005L, expired.get(0).getSubjectId());
        assertEquals(CHAT_TURN, expired.get(0).getSubjectKind());
        assertTrue(
                leaseMapper.workspaceIdsWithExpiredLeases(0, 500).contains(workspace.getId()),
                "The discovery helper must find a workspace holding an expired lease");
        assertFalse(
                leaseMapper.workspaceIdsWithExpiredLeases(workspace.getId(), 500)
                        .contains(workspace.getId()),
                "The discovery cursor must be exclusive so a pass advances");

        assertTrue(takeOverForSettlement(expiredKey, expiredLease.epoch() + 7L).isEmpty());
        AiRunLease takeover =
                takeOverForSettlement(expiredKey, expiredLease.epoch()).orElseThrow();

        assertEquals(expiredLease.epoch() + 1L, takeover.epoch());
        assertEquals(AiRunLeaseOutcome.LOST, leaseService.renew(expiredLease));
        assertEquals(AiRunLeaseOutcome.HELD, leaseService.renew(takeover));
        assertTrue(
                leaseMapper.findExpiredLeases(workspace.getId(), List.of(CHAT_TURN), 10)
                        .isEmpty());
    }

    @Test
    void theReapDeletesOnlyTombstonesOlderThanTheRetentionWindow() {
        AiRunLeaseKey oldKey = key(AiRunLeaseSubject.CHAT_TURN, 3007L);
        AiRunLeaseKey freshKey = key(AiRunLeaseSubject.CHAT_TURN, 3008L);
        acquire(oldKey);
        acquire(freshKey);
        assertTrue(release(oldKey));
        assertTrue(release(freshKey));
        ageTombstone(oldKey);

        assertEquals(1, leaseService.reapTombstones(workspace.getId(), 3600, 50));

        assertEquals(1, leaseCount());
        assertNotNull(leaseRow(freshKey).get("released_at"));
    }

    /**
     * Deleting a tombstone restarts that key's fencing epoch at 1, which is only safe while the
     * retention window provably exceeds the longest run of that kind. {@code generation-max-lifetime}
     * bounds a chat turn and the property validation keeps the retention above it; nothing bounds an
     * agent run yet, so its tombstone is kept rather than have its fence reset under an owner that
     * may still act.
     */
    @Test
    void theReapKeepsTombstonesOfASubjectKindWhoseRunLengthIsNotYetBounded() {
        AiRunLeaseKey turnKey = key(AiRunLeaseSubject.CHAT_TURN, 3009L);
        AiRunLeaseKey agentKey = key(AiRunLeaseSubject.AGENT_RUN, 3009L);
        acquire(turnKey);
        acquire(agentKey);
        assertTrue(release(turnKey));
        assertTrue(release(agentKey));
        ageTombstone(turnKey);
        ageTombstone(agentKey);

        assertEquals(1, leaseService.reapTombstones(workspace.getId(), 3600, 50));

        assertEquals(1, leaseCount());
        assertNotNull(leaseRow(agentKey).get("released_at"));
        assertEquals(2L, acquire(agentKey).epoch());
    }

    /**
     * The release runs inside the durable terminal transaction that carries the subject's result,
     * so it must never be the statement that fails. A database clock stepped backwards between
     * acquisition and release (NTP step, VM snapshot restore, host correction) would make a bare
     * {@code CURRENT_TIMESTAMP(6)} deadline earlier than {@code acquired_at} and the expiry CHECK
     * would refuse the whole transaction.
     */
    @Test
    void aReleaseSurvivesADatabaseClockThatSteppedBackwardsSinceAcquisition() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 3010L);
        acquire(key);
        jdbcTemplate.update(
                "UPDATE ai_run_lease"
                        + " SET acquired_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 5 SECOND),"
                        + " heartbeat_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 5 SECOND),"
                        + " expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 50 SECOND)"
                        + " WHERE workspace_id = ? AND subject_kind = ? AND subject_id = ?",
                key.workspaceId(), key.subject().wireKey(), key.subjectId());

        assertTrue(release(key));

        Map<String, Object> row = leaseRow(key);
        assertNull(row.get("owner"));
        assertNotNull(row.get("released_at"));
        assertEquals(
                0L,
                ((Number) row.get("ttl_micros")).longValue(),
                "A release may not push the deadline past acquisition, only back to it");
    }

    /**
     * Once a lease is past its deadline a settler is entitled to take it over, so a returning owner
     * has to learn it lost rather than push the deadline out and race that settler. The refusal is
     * in SQL because the database clock is the only one every instance shares: an owner whose
     * monotonic clock did not advance across a suspended host is exactly the case its own
     * self-fence cannot see.
     */
    @Test
    void aRenewalIsRefusedOnceTheLeaseHasAlreadyExpired() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 3014L);
        AiRunLease held = acquire(key);
        expire(key);
        Object expiredDeadline = leaseRow(key).get("expires_at");

        assertEquals(AiRunLeaseOutcome.LOST, leaseService.renew(held));

        assertEquals(
                1,
                leaseMapper.findExpiredLeases(workspace.getId(), List.of(CHAT_TURN), 10).size());
        assertEquals(
                expiredDeadline,
                leaseRow(key).get("expires_at"),
                "A refused renewal must leave the expired deadline exactly where it was");
        assertTrue(
                takeOverForSettlement(key, held.epoch()).isPresent(),
                "A settler must still be able to claim the lease the renewal was refused for");
    }

    /**
     * A renewal computes its deadline from the database clock, so a clock that stepped backwards by
     * more than the lifetime would place the new deadline before {@code acquired_at} and the expiry
     * CHECK would reject every renewal, stopping a healthy owner through its own self-fence.
     */
    @Test
    void aRenewalSurvivesADatabaseClockThatSteppedBackwardsByMoreThanTheLifetime() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 3013L);
        AiRunLease held = acquire(key);
        jdbcTemplate.update(
                "UPDATE ai_run_lease"
                        + " SET acquired_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 120 SECOND),"
                        + " heartbeat_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 120 SECOND),"
                        + " expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 165 SECOND)"
                        + " WHERE workspace_id = ? AND subject_kind = ? AND subject_id = ?",
                key.workspaceId(), key.subject().wireKey(), key.subjectId());

        assertEquals(AiRunLeaseOutcome.HELD, leaseService.renew(held));

        Map<String, Object> row = leaseRow(key);
        assertEquals(held.owner(), row.get("owner"));
        assertNull(row.get("released_at"));
        assertEquals(
                0L,
                ((Number) row.get("ttl_micros")).longValue(),
                "A renewal under a stepped-back clock holds the deadline at acquisition rather"
                        + " than violating the expiry constraint");
    }

    /**
     * A settler releases its takeover through the same entry point an owner uses, in the one
     * transaction the settlement contract requires. An unregistered takeover token would make that
     * release a silent no-op, leaving a held settlement lease that every later sweep pass
     * rediscovers as an expired lease on an already-terminal subject.
     */
    @Test
    void aSettlerCanTombstoneTheLeaseItJustTookOver() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 3011L);
        AiRunLease abandoned = acquire(key);
        expire(key);

        AiRunLease takeover = transactions.execute(status -> {
            AiRunLease claimed =
                    leaseService.takeOverForSettlement(key, abandoned.epoch()).orElseThrow();
            assertTrue(
                    leaseService.releaseHeldInCurrentTransaction(key),
                    "A settler must release the takeover it just registered");
            return claimed;
        });

        Map<String, Object> row = leaseRow(key);
        assertNull(row.get("owner"));
        assertNotNull(row.get("released_at"));
        assertEquals(takeover.epoch(), ((Number) row.get("epoch")).longValue());
        assertTrue(
                leaseMapper.findExpiredLeases(workspace.getId(), List.of(CHAT_TURN), 10)
                        .isEmpty());
    }

    /**
     * The JVM-local token must never disagree with the committed row. A claim that rolls back
     * leaves no row, so it must leave no token either — otherwise the map grows an entry nothing
     * ever prunes and this instance offers a fence it does not hold.
     */
    @Test
    void aClaimThatRollsBackLeavesNeitherARowNorAToken() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 3012L);

        transactions.execute(status -> {
            AiRunLease claimed = leaseService.acquireInCurrentTransaction(key, freshGuard());
            status.setRollbackOnly();
            return claimed;
        });

        assertEquals(0, leaseCount());
        assertTrue(leaseRegistry.find(key).isEmpty());
    }

    /**
     * A terminal transaction that rolls back leaves the lease held, so this instance must keep its
     * token for the retry. Forgetting it inline would strand a held lease a settler then takes over
     * as if the owner had died, even though the run ended cleanly.
     */
    @Test
    void aReleaseThatRollsBackKeepsTheTokenAndTheHeldRow() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 3013L);
        AiRunLease held = acquire(key);

        transactions.execute(status -> {
            boolean released = leaseService.releaseHeldInCurrentTransaction(key);
            status.setRollbackOnly();
            return released;
        });

        assertEquals(Optional.of(held), leaseRegistry.find(key));
        assertEquals(leaseIdentity.owner(), leaseRow(key).get("owner"));
        assertNull(leaseRow(key).get("released_at"));

        assertTrue(release(key));
        assertNotNull(leaseRow(key).get("released_at"));
    }

    /**
     * A settler that never opened its own transaction is refused rather than handed a takeover
     * that commits on its own.
     *
     * <p>The fence that stops a revived owner is the subject's terminal status, and it closes at
     * the settler's terminal commit — not at the epoch bump. A standalone takeover would therefore
     * leave a window in which the epoch has already moved while the subject still reads as
     * running, so the owner the settler just fenced out can settle the subject itself and retire
     * the settler's lease on the way out. {@code MANDATORY} propagation makes that mistake
     * impossible to make quietly in a subject handler nobody has written yet.
     */
    @Test
    void aTakeoverOutsideASettlementTransactionIsRefusedAndChangesNothing() {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 3015L);
        AiRunLease held = acquire(key);
        expire(key);
        Map<String, Object> before = leaseRow(key);

        assertThrows(
                IllegalTransactionStateException.class,
                () -> leaseService.takeOverForSettlement(key, held.epoch()));

        Map<String, Object> after = leaseRow(key);
        assertEquals(before.get("owner"), after.get("owner"));
        assertEquals(before.get("epoch"), after.get("epoch"));
        assertEquals(before.get("expires_at"), after.get("expires_at"));
        assertTrue(leaseRegistry.find(key).isPresent());
    }

    /**
     * The reap's workspace discovery must ask for the same subject kinds its delete will touch.
     * When it does not, a workspace whose only aged tombstone belongs to a kind the delete refuses
     * is returned by every cursor cycle, for the life of the tenant, and deletes nothing each time.
     */
    @Test
    void theReapDiscoveryIgnoresAWorkspaceWhoseOnlyAgedTombstoneIsNotReapable() {
        AiRunLeaseKey agentKey = key(AiRunLeaseSubject.AGENT_RUN, 3016L);
        acquire(agentKey);
        assertTrue(release(agentKey));
        ageTombstone(agentKey);

        assertFalse(
                reapableWorkspaceIds().contains(workspace.getId()),
                "A workspace holding only an unreapable aged tombstone must not be rediscovered");

        AiRunLeaseKey turnKey = key(AiRunLeaseSubject.CHAT_TURN, 3016L);
        acquire(turnKey);
        assertTrue(release(turnKey));
        ageTombstone(turnKey);

        assertTrue(
                reapableWorkspaceIds().contains(workspace.getId()),
                "A workspace holding a reapable aged tombstone must still be discovered");
        assertEquals(1, leaseService.reapTombstones(workspace.getId(), 3600, 50));
    }

    /**
     * The reap's workspace discovery carries a workspace <em>cursor</em>, not a workspace equality,
     * so an index that leads with {@code workspace_id} cannot bound it: the cursor range consumes
     * the leading column and the retention predicate degrades to a per-entry condition. In the
     * steady state nothing is old enough to reap, so the page limit never short-circuits and the
     * probe reads every retained tombstone above the cursor to prove an empty result — every sweep,
     * on every instance, for every catalog. Only an index leading with {@code released_at} turns it
     * back into a range bounded by the retention cutoff.
     */
    @Test
    void theReapDiscoveryHasAReleaseTimeLeadingIndexToBoundIt() {
        assertEquals(
                List.of("released_at", "workspace_id", "subject_kind"),
                indexColumns("idx_ai_run_lease_reapable"),
                "The reap discovery probe needs an index that leads with released_at");
    }

    private List<String> indexColumns(String indexName) {
        return jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_run_lease'"
                        + " AND INDEX_NAME = ? ORDER BY SEQ_IN_INDEX",
                String.class,
                indexName);
    }

    private List<Integer> reapableWorkspaceIds() {
        return leaseMapper.workspaceIdsWithReapableTombstones(
                workspace.getId() - 1, AiRunLeaseSubject.reapableWireKeys(), 3600, 500);
    }
}
