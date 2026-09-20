package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.ai.lease.AiRunLeaseIdentity;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseKey;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubject;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSweeper;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.AiChatTurnRef;
import ooo.klae.connex.backend.beans.AiRunLeaseRow;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AiRunLeaseMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * The #1788 exit-criteria drill, against the real database.
 *
 * <p>A turn whose owner stops heartbeating is detected and settled by a <em>different</em> owner
 * identity inside the lease window, and the stale owner can no longer write a terminal state
 * afterwards. A second JVM is deliberately not spawned: the fence is pure {@code (owner, epoch)}
 * data with no process affinity, so a foreign owner string in the lease row is a faithful stand-in
 * for a dead instance and the settler here is the same real service a sweeper dispatches. The one
 * thing this shape cannot prove is that a genuinely separate process observes the same durable
 * state, which is verified once on staging by restarting an instance mid-turn.
 *
 * <p>It also pins the atomicity the status fence rests on. The fence is the turn's own status and
 * it closes at the settler's terminal commit, not at the takeover, so the takeover and the
 * terminal write must be one transaction: a settlement that fails after taking the lease over has
 * to leave the lease exactly as it found it. Splitting them puts the epoch bump on disk while the
 * turn still reads running, which is the window a revived owner walks through.
 *
 * <p>The last two drills run a whole scheduled pass. Both sweepers' own tests mock their mappers,
 * so without these nothing drives discovery, workspace routing, and settlement together through
 * the real tenant-scope interceptor and the real discovery statements. This context has no web
 * environment, so the calling thread is off the request thread exactly as the scheduler thread is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiChatTurnOrphanSettlementIntegrationTest {

    private static final String CHAT_TURN = AiRunLeaseSubject.CHAT_TURN.wireKey();
    private static final String DEAD_OWNER = "deadbeef-0000-4000-8000-000000000001";
    private static final int LEASE_TTL_SECONDS = 45;
    private static final int LIFETIME_SECONDS = 60;
    private static final int SETTLEMENT_TTL_SECONDS = 30;

    @Autowired AiChatTurnOrphanSettlementService settlementService;
    @Autowired AiChatTurnPersistenceService persistenceService;
    @Autowired AiRunLeaseSweeper leaseSweeper;
    @Autowired AiChatTurnLifetimeSweeper lifetimeSweeper;
    @Autowired AiRunLeaseIdentity leaseIdentity;
    @Autowired AiRunLeaseMapper leaseMapper;
    @Autowired AiChatMapper chatMapper;
    @Autowired OrganizationMapper organizationMapper;
    @Autowired WorkspaceMapper workspaceMapper;
    @Autowired UserMapper userMapper;
    @Autowired TenantContext tenantContext;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    private Organization organization;
    private Workspace workspace;
    private User member;
    private int sessionId;
    private TransactionTemplate transactions;

    @BeforeEach
    void createTenantFixture() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Orphan settlement " + unique);
        organization.setSlug("orphan-settlement-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Orphan settlement " + unique);
        workspace.setSlug("orphan-settlement-" + unique);
        workspaceMapper.insert(workspace);

        member = new User();
        member.setUsername("orphan-settlement-" + unique);
        member.setDisplayName("Orphan settlement " + unique);
        member.setEmail("orphan-settlement-" + unique + "@example.com");
        member.setPasswordHash("hash-" + unique);
        member.setTimezone("UTC");
        userMapper.insert(member);
        workspaceMapper.addMember(workspace.getId(), member.getId(), "owner");

        transactions = new TransactionTemplate(transactionManager);
        tenantContext.set(
                workspace.getId(), organization.getId(), member.getId(), "owner", null);
        sessionId = insertSession();
    }

    @AfterEach
    void removeTenantFixture() {
        tenantContext.clear();
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM job_run WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update(
                    "DELETE FROM ai_run_lease WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update(
                    "DELETE FROM ai_chat_session WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update(
                    "DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
    }

    @Test
    void aTurnWhoseOwnerStoppedHeartbeatingIsSettledByADifferentOwnerIdentity() {
        int turnId = insertTurn("running");
        insertDeadOwnerLease(turnId, 3L);
        AiRunLeaseKey key = key(turnId);

        assertNotEquals(DEAD_OWNER, leaseIdentity.owner());
        assertEquals(List.of(turnId), expiredLeaseSubjectIds());
        assertTrue(settlementService.settleOrphan(key, 3L));

        Map<String, Object> turn = turnRow(turnId);
        assertEquals("failed", turn.get("status"));
        assertEquals(AiAssistantTerminalReasons.OWNER_LOST, turn.get("terminal_reason"));

        Map<String, Object> lease = leaseRow(key);
        assertNull(lease.get("owner"));
        assertNotNull(lease.get("released_at"));
        assertEquals(4L, ((Number) lease.get("epoch")).longValue());
    }

    @Test
    void theStaleOwnerCanNeitherRenewNorSettleTheTurnAfterTheTakeover() {
        int turnId = insertTurn("running");
        insertDeadOwnerLease(turnId, 3L);
        AiRunLeaseKey key = key(turnId);

        assertTrue(settlementService.settleOrphan(key, 3L));

        assertEquals(
                0,
                leaseMapper.renew(
                        workspace.getId(), CHAT_TURN, turnId, DEAD_OWNER, 3L, LEASE_TTL_SECONDS));
        assertEquals(
                0, leaseMapper.tombstone(workspace.getId(), CHAT_TURN, turnId, DEAD_OWNER, 3L));
        assertEquals(
                0,
                leaseMapper.takeOverForSettlement(
                        workspace.getId(), CHAT_TURN, turnId, DEAD_OWNER, 3L,
                        SETTLEMENT_TTL_SECONDS));
        assertEquals(
                0,
                chatMapper.updateTurnTerminal(
                        workspace.getId(), sessionId, turnId, "resolved", null, "running", null));
    }

    @Test
    void aSettlementThatFailsAfterTheTakeoverLeavesTheLeaseExactlyAsItFoundIt() {
        int turnId = insertUnsettleableStreamedTurn();
        insertDeadOwnerLease(turnId, 3L);
        AiRunLeaseKey key = key(turnId);

        assertThrows(
                IllegalStateException.class, () -> settlementService.settleOrphan(key, 3L));

        Map<String, Object> lease = leaseRow(key);
        assertEquals(DEAD_OWNER, lease.get("owner"));
        assertEquals(3L, ((Number) lease.get("epoch")).longValue());
        assertNull(lease.get("released_at"));
        assertEquals("running", turnRow(turnId).get("status"));
    }

    /**
     * Two settlements of one turn that are provably in flight at the same time.
     *
     * <p>The overlap is forced rather than hoped for: a third transaction holds the settlement
     * path's first lock — the session row — until both settlers have entered {@code settleOrphan}
     * and neither has been able to finish. Without that barrier the two calls commonly run
     * strictly one after the other, and the exclusive-or below then holds for a reason the drill
     * was not written to test.
     *
     * <p>What this can and cannot refute, stated rather than overclaimed. It refutes a settlement
     * path on which two concurrent settlers both write a terminal state, and it refutes one on
     * which the second settler bumps the fencing epoch a second time. It cannot single out which
     * of the three serialising mechanisms did the work — the session lock, the turn lock, or the
     * epoch compare-and-set — because the loser's observable result is identical under all three:
     * it changes no row and returns false. {@code AiRunLeaseConcurrencyIntegrationTest} isolates
     * the lease row lock; this drill pins the outcome the member depends on.
     */
    @Test
    void twoSettlersRacingOneTurnProduceExactlyOneTerminalWrite() throws Exception {
        int turnId = insertTurn("running");
        insertDeadOwnerLease(turnId, 3L);
        AiRunLeaseKey key = key(turnId);
        CountDownLatch sessionLocked = new CountDownLatch(1);
        CountDownLatch releaseSession = new CountDownLatch(1);
        CountDownLatch settlersEntered = new CountDownLatch(2);
        CountDownLatch settlersFinished = new CountDownLatch(2);
        ExecutorService threads = Executors.newFixedThreadPool(3);
        try {
            threads.submit(() -> inTenant(() -> transactions.execute(status -> {
                chatMapper.getSessionByIdForMaintenanceUpdate(workspace.getId(), sessionId);
                sessionLocked.countDown();
                awaitQuietly(releaseSession);
                return null;
            })));
            assertTrue(
                    sessionLocked.await(30, TimeUnit.SECONDS),
                    "The barrier never took the session row lock");

            Callable<Boolean> settle = () -> {
                settlersEntered.countDown();
                try {
                    return inTenant(() -> settlementService.settleOrphan(key, 3L));
                } finally {
                    settlersFinished.countDown();
                }
            };
            Future<Boolean> first = threads.submit(settle);
            Future<Boolean> second = threads.submit(settle);
            assertTrue(
                    settlersEntered.await(30, TimeUnit.SECONDS),
                    "Both settlers never entered the settlement");
            assertFalse(
                    settlersFinished.await(1, TimeUnit.SECONDS),
                    "A settlement completed while the session row lock was held, so the two"
                            + " settlements never overlapped");
            releaseSession.countDown();

            assertTrue(first.get(60, TimeUnit.SECONDS) ^ second.get(60, TimeUnit.SECONDS));
        } finally {
            releaseSession.countDown();
            threads.shutdownNow();
        }
        Map<String, Object> turn = turnRow(turnId);
        assertEquals("failed", turn.get("status"));
        assertEquals(AiAssistantTerminalReasons.OWNER_LOST, turn.get("terminal_reason"));
        assertEquals(4L, ((Number) leaseRow(key).get("epoch")).longValue());
    }

    @Test
    void anAlreadyTerminalTurnRetiresItsLeaseWithoutASecondTerminalWrite() {
        int turnId = insertTurn("resolved");
        insertDeadOwnerLease(turnId, 3L);
        AiRunLeaseKey key = key(turnId);

        assertFalse(settlementService.settleOrphan(key, 3L));

        Map<String, Object> lease = leaseRow(key);
        assertNull(lease.get("owner"));
        assertNotNull(lease.get("released_at"));
        assertEquals(3L, ((Number) lease.get("epoch")).longValue());
        assertEquals("resolved", turnRow(turnId).get("status"));
    }

    @Test
    void aLeasedTurnBelongsToTheLeasePassAndAnUnleasedOneToTheLifetimePass() {
        int leasedTurnId = insertTurn("running");
        insertDeadOwnerLease(leasedTurnId, 3L);
        int unleasedTurnId = insertTurn("running");
        int tombstonedTurnId = insertTurn("running");
        insertDeadOwnerLease(tombstonedTurnId, 3L);
        jdbcTemplate.update(
                "UPDATE ai_run_lease SET owner = NULL, released_at = CURRENT_TIMESTAMP(6)"
                        + " WHERE workspace_id = ? AND subject_kind = ? AND subject_id = ?",
                workspace.getId(), CHAT_TURN, tombstonedTurnId);
        ageTurns();

        assertEquals(List.of(leasedTurnId), expiredLeaseSubjectIds());
        assertEquals(
                List.of(unleasedTurnId),
                chatMapper.findUnleasedStaleTurnRefs(workspace.getId(), LIFETIME_SECONDS, 50)
                        .stream()
                        .map(AiChatTurnRef::id)
                        .toList());
        assertTrue(
                chatMapper.workspaceIdsWithUnleasedStaleTurns(
                                workspace.getId() - 1, LIFETIME_SECONDS, 50)
                        .contains(workspace.getId()));
    }

    @Test
    void anUnleasedStaleTurnExpiresWithTheDurableTimeoutRatherThanAnOwnershipLoss() {
        int turnId = insertTurn("running");
        ageTurns();

        assertTrue(
                persistenceService.expireUnleasedTurn(
                        workspace.getId(), sessionId, turnId, LIFETIME_SECONDS));

        Map<String, Object> turn = turnRow(turnId);
        assertEquals("timed_out", turn.get("status"));
        assertEquals("generation_timeout", turn.get("terminal_reason"));
        assertFalse(settlementService.settleOrphan(key(turnId), 3L));
    }

    @Test
    void anUnleasedTurnInsideTheLifetimeIsLeftAlone() {
        int turnId = insertTurn("running");

        assertFalse(
                persistenceService.expireUnleasedTurn(
                        workspace.getId(), sessionId, turnId, LIFETIME_SECONDS));

        assertEquals("running", turnRow(turnId).get("status"));
    }

    @Test
    void aScheduledLeasePassDiscoversAndSettlesAnOrphanedTurnEndToEnd() {
        int turnId = insertTurn("running");
        insertDeadOwnerLease(turnId, 3L);

        sweepUntil(leaseSweeper::sweep, () -> !"running".equals(turnRow(turnId).get("status")));

        Map<String, Object> turn = turnRow(turnId);
        assertEquals("failed", turn.get("status"));
        assertEquals(AiAssistantTerminalReasons.OWNER_LOST, turn.get("terminal_reason"));
        Map<String, Object> lease = leaseRow(key(turnId));
        assertNull(lease.get("owner"));
        assertNotNull(lease.get("released_at"));
        assertEquals(4L, ((Number) lease.get("epoch")).longValue());
        assertEquals(1, jobRuns(JobRunRecorder.AI_RUN_LEASE_SWEEP));
    }

    @Test
    void aScheduledLifetimePassDiscoversAndExpiresAnUnleasedStaleTurnEndToEnd() {
        int turnId = insertTurn("running");
        ageTurns();

        sweepUntil(lifetimeSweeper::sweep, () -> !"running".equals(turnRow(turnId).get("status")));

        Map<String, Object> turn = turnRow(turnId);
        assertEquals("timed_out", turn.get("status"));
        assertEquals("generation_timeout", turn.get("terminal_reason"));
        assertEquals(1, jobRuns(JobRunRecorder.AI_CHAT_TURN_LIFETIME_SWEEP));
    }

    private <T> T inTenant(Supplier<T> work) {
        tenantContext.set(workspace.getId(), organization.getId(), member.getId(), "owner", null);
        try {
            return work.get();
        } finally {
            tenantContext.clear();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Settlement barrier never opened");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the session row lock");
        }
    }

    /**
     * Runs scheduled passes until this fixture's turn settles. A pass is paged by a rotating
     * workspace cursor and a shared schema can hold other fixtures ahead of this one, so the bound
     * is a number of passes, never a wall-clock wait.
     */
    private static void sweepUntil(Runnable pass, Supplier<Boolean> settled) {
        for (int passes = 0; passes < 64 && !settled.get(); passes++) {
            pass.run();
        }
    }

    private int jobRuns(String jobName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_run WHERE workspace_id = ? AND job_name = ?",
                Integer.class,
                workspace.getId(),
                jobName);
        return count == null ? 0 : count;
    }

    private AiRunLeaseKey key(int turnId) {
        return new AiRunLeaseKey(workspace.getId(), AiRunLeaseSubject.CHAT_TURN, turnId);
    }

    private List<Integer> expiredLeaseSubjectIds() {
        return leaseMapper.findExpiredLeases(workspace.getId(), List.of(CHAT_TURN), 50).stream()
                .map(AiRunLeaseRow::getSubjectId)
                .map(Math::toIntExact)
                .toList();
    }

    private int insertSession() {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(member.getId());
        session.setTitle("Orphan settlement drill");
        session.setTitleUserSet(false);
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session.getId();
    }

    private int insertTurn(String status) {
        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(workspace.getId());
        turn.setSessionId(sessionId);
        turn.setRequestedByUserId(member.getId());
        turn.setStatus(status);
        turn.setPrivacyMode("masked");
        turn.setStreamed(false);
        chatMapper.insertTurn(turn);
        return turn.getId();
    }

    /**
     * Creates a running streamed turn whose special-care partial answer cannot be purged.
     *
     * <p>The terminal screen must reset a partial it may not retain, and {@code cancel_requested_at}
     * makes that reset match no row, so the settlement fails after it has taken the lease over —
     * the one moment the atomicity of the takeover and the terminal write is observable. Every
     * predicate here is a production one; nothing is stubbed.
     *
     * @return the turn id
     */
    private int insertUnsettleableStreamedTurn() {
        int turnId = insertTurn("running");
        jdbcTemplate.update(
                "UPDATE ai_chat_turn"
                        + " SET streamed = TRUE, partial_content = 'criminal record',"
                        + " partial_content_utf16_offset = 15,"
                        + " cancel_requested_at = CURRENT_TIMESTAMP(6)"
                        + " WHERE workspace_id = ? AND id = ?",
                workspace.getId(), turnId);
        return turnId;
    }

    private void insertDeadOwnerLease(int turnId, long epoch) {
        jdbcTemplate.update(
                "INSERT INTO ai_run_lease"
                        + " (workspace_id, subject_kind, subject_id, owner, epoch,"
                        + " acquired_at, heartbeat_at, expires_at, released_at)"
                        + " VALUES (?, ?, ?, ?, ?,"
                        + " DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 MINUTE),"
                        + " DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 MINUTE),"
                        + " DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 MINUTE), NULL)",
                workspace.getId(), CHAT_TURN, turnId, DEAD_OWNER, epoch);
    }

    private void ageTurns() {
        jdbcTemplate.update(
                "UPDATE ai_chat_turn"
                        + " SET updated_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 10 MINUTE)"
                        + " WHERE workspace_id = ?",
                workspace.getId());
    }

    private Map<String, Object> turnRow(int turnId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, terminal_reason FROM ai_chat_turn"
                        + " WHERE workspace_id = ? AND id = ?",
                workspace.getId(), turnId);
    }

    private Map<String, Object> leaseRow(AiRunLeaseKey key) {
        return jdbcTemplate.queryForMap(
                "SELECT owner, epoch, released_at FROM ai_run_lease"
                        + " WHERE workspace_id = ? AND subject_kind = ? AND subject_id = ?",
                key.workspaceId(), key.subject().wireKey(), key.subjectId());
    }
}
