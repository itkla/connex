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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;

import ooo.klae.connex.backend.beans.AiRunLeaseRow;

/**
 * Real-database behaviour of the run lease: MySQL owns every deadline, the {@code (owner, epoch)}
 * pair is a fence, the release-state CHECK is enforced by the database, and the catalog-pinned
 * discovery and reap predicates find exactly the rows a sweeper must act on.
 */
class AiRunLeaseIntegrationTest extends AbstractAiRunLeaseIntegrationTest {

    /**
     * Timestamp columns may only ever be assigned from the database clock or cleared. A bound
     * parameter here would put a lease deadline on a JVM clock and let instance skew move it.
     */
    private static final Pattern TIMESTAMP_ASSIGNMENT = Pattern.compile(
            "(acquired_at|heartbeat_at|expires_at|released_at)\\s*=\\s*(?!"
                    + "CURRENT_TIMESTAMP\\(6\\)|DATE_ADD\\(CURRENT_TIMESTAMP\\(6\\)|NULL)(\\S+)");

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

    @Test
    void expiredLeaseDiscoveryIsWorkspacePagedAndTakeoverIsEpochFenced() {
        AiRunLeaseKey expiredKey = key(AiRunLeaseSubject.CHAT_TURN, 3005L);
        AiRunLeaseKey liveKey = key(AiRunLeaseSubject.AGENT_RUN, 3006L);
        AiRunLease expiredLease = acquire(expiredKey);
        acquire(liveKey);
        expire(expiredKey);

        List<AiRunLeaseRow> expired = leaseMapper.findExpiredLeases(workspace.getId(), 10);
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

        assertTrue(leaseService.takeOverForSettlement(expiredKey, expiredLease.epoch() + 7L).isEmpty());
        AiRunLease takeover = leaseService.takeOverForSettlement(expiredKey, expiredLease.epoch())
                .orElseThrow();

        assertEquals(expiredLease.epoch() + 1L, takeover.epoch());
        assertEquals(AiRunLeaseOutcome.LOST, leaseService.renew(expiredLease));
        assertEquals(AiRunLeaseOutcome.HELD, leaseService.renew(takeover));
        assertTrue(leaseMapper.findExpiredLeases(workspace.getId(), 10).isEmpty());
    }

    @Test
    void theReapDeletesOnlyTombstonesOlderThanTheRetentionWindow() {
        AiRunLeaseKey oldKey = key(AiRunLeaseSubject.CHAT_TURN, 3007L);
        AiRunLeaseKey freshKey = key(AiRunLeaseSubject.CHAT_TURN, 3008L);
        acquire(oldKey);
        acquire(freshKey);
        assertTrue(release(oldKey));
        assertTrue(release(freshKey));
        jdbcTemplate.update(
                "UPDATE ai_run_lease SET released_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 HOUR)"
                        + " WHERE workspace_id = ? AND subject_kind = ? AND subject_id = ?",
                oldKey.workspaceId(), oldKey.subject().wireKey(), oldKey.subjectId());

        assertEquals(1, leaseService.reapTombstones(workspace.getId(), 3600, 50));

        assertEquals(1, leaseCount());
        assertNotNull(leaseRow(freshKey).get("released_at"));
    }
}
