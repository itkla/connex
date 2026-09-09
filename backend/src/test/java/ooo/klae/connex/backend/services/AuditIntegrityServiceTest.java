package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.mappers.AuditIntegrityMapper;
import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;

class AuditIntegrityServiceTest extends AbstractServiceTest {

    private static final String HASH_GENESIS = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String TEST_SECRET = "test-audit-integrity-hmac-secret-change-me";

    @Autowired private AuditService auditService;
    @Autowired private AuditIntegrityService auditIntegrityService;
    @Autowired private AuditLogMapper auditLogMapper;
    @Autowired private AuditIntegrityMapper auditIntegrityMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RoleService roleService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private AuthService authService;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private ooo.klae.connex.backend.observability.SecuritySignalMetrics securitySignalMetrics;
    @Autowired private RoleMapper roleMapper;
    private final List<Integer> committedUserIds = new ArrayList<>();
    private Integer committedRoleId;

    /** Restores the shared approver pool even when the committed boundary test fails. */
    @AfterEach
    void cleanUpCommittedSecuritySignalFixtures() {
        if (committedUserIds.isEmpty()) {
            return;
        }
        authenticateAs(currentUser, workspace.getId());
        if (committedRoleId != null) {
            roleMapper.clearPermissions(workspace.getId(), committedRoleId);
            roleMapper.deleteRole(workspace.getId(), committedRoleId);
        }
        for (int userId : committedUserIds.reversed()) {
            workspaceMapper.removeMember(workspace.getId(), userId);
            userMapper.delete(userId);
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void committedRoleMutationsAndActualLoginFailureEmitSecuritySignals() {
        committedUserIds.add(currentUser.getId());
        committedRoleId = roleService.createRole(
                workspace.getId(), currentUser.getId(), "Alert test " + unique(),
                List.of("AUDIT_READ")).getId();
        var target = newUser();
        committedUserIds.add(target.getId());
        workspaceService.changeMemberRole(workspace.getId(), currentUser.getId(), target.getId(), "admin");
        assertTrue(meterRegistry.get("connex.security.permission.change.timestamp")
                .tag("scope", "workspace:" + workspace.getId()).gauge().value() > 0);
        var rows = auditLogMapper.findRecent(workspace.getId(), 20, 0);
        assertTrue(rows.stream().anyMatch(row -> "workspace.role.create".equals(row.getAction())));
        assertTrue(rows.stream().anyMatch(row -> "workspace.member.role".equals(row.getAction())));
        tenantContext.clear();
        var counter = meterRegistry.counter("connex.security.authentication.failures", "scope", "unattributed");
        double before = counter.count();
        assertThrows(org.springframework.security.authentication.BadCredentialsException.class,
                () -> authService.login(new ooo.klae.connex.backend.dto.LoginDto("missing-" + unique(), "incorrect"),
                        new org.springframework.mock.web.MockHttpServletRequest(),
                        new org.springframework.mock.web.MockHttpServletResponse()));
        assertEquals(before + 1, counter.count());
        System.out.println("BOUNDARY PASS persisted role grant; persisted membership elevation; actual rejected login increments unattributed counter");
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void committedRoleReturnsAndLaterCallbackRunsWhenRegistryThrows() {
        committedUserIds.add(currentUser.getId());
        var target = org.springframework.test.util.AopTestUtils.getTargetObject(auditIntegrityService);
        String roleName = "Failure test " + unique();
        var later = new java.util.concurrent.atomic.AtomicBoolean();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var failing = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            failing.config().meterFilter(new io.micrometer.core.instrument.config.MeterFilter() {
                @Override
                public io.micrometer.core.instrument.Meter.Id map(io.micrometer.core.instrument.Meter.Id id) {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("sensitive-registry-probe");
                }
            });
            org.springframework.test.util.ReflectionTestUtils.setField(target, "securitySignalMetrics",
                    new ooo.klae.connex.backend.observability.SecuritySignalMetrics(failing, java.time.Clock.systemUTC()));
            var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> {
                committedRoleId = roleService.createRole(workspace.getId(), currentUser.getId(),
                        roleName, List.of("AUDIT_READ")).getId();
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                later.set(true);
                            }
                        });
            }));
            assertEquals(1, attempts.get());
            assertTrue(later.get());
            assertNotNull(roleMapper.findRole(workspace.getId(), committedRoleId));
            assertTrue(auditLogMapper.findRecent(workspace.getId(), 20, 0).stream()
                    .anyMatch(row -> "workspace.role.create".equals(row.getAction())
                            && java.util.Objects.equals(workspace.getId(), row.getEntityId())
                            && roleName.equals(row.getTargetLabel())));
        } finally {
            failing.close();
            org.springframework.test.util.ReflectionTestUtils.setField(target, "securitySignalMetrics", securitySignalMetrics);
        }
    }

    @Test
    void authenticStoredRowPassesAndModifiedHmacPayloadEmitsAnomaly() {
        String summary = "alert-hmac-" + unique();
        auditService.record("test.integrity", "company", 1, "Acme", summary, null);
        AuditLog row = findBySummary(auditLogMapper.findRecent(workspace.getId(), 20, 0), summary);
        assertTrue(auditIntegrityService.hasValidIntegrity(row));
        auditService.recent(20, 0);
        row.setSummary("modified fixture payload");
        assertFalse(auditIntegrityService.hasValidIntegrity(row));
        auditIntegrityService.observeStoredIntegrity(row);
        assertTrue(meterRegistry.get("connex.security.audit.integrity.anomaly.timestamp")
                .tag("scope", "workspace:" + workspace.getId()).gauge().value() > 0);
        System.out.println("BOUNDARY PASS database-signed HMAC validates; modified in-memory payload emits anomaly before disclosure");
    }

    @Test
    void workspaceEventsChainByWorkspaceScope() {
        String firstSummary = "first-" + unique();
        String secondSummary = "second-" + unique();

        auditService.record("test.integrity", "company", 1, "Acme", firstSummary, null);
        auditService.record("test.integrity", "company", 1, "Acme", secondSummary, null);

        AuditLog first = findBySummary(auditService.recent(20, 0), firstSummary);
        AuditLog second = findBySummary(auditService.recent(20, 0), secondSummary);

        assertEquals("workspace", first.getChainScopeType());
        assertEquals(workspace.getId(), first.getChainScopeId());
        assertEquals("workspace", second.getChainScopeType());
        assertEquals(workspace.getId(), second.getChainScopeId());
        assertNotNull(first.getRowHash());
        assertEquals(64, first.getRowHash().length());
        assertEquals(first.getChainIndex() + 1, second.getChainIndex());
        assertEquals(first.getRowHash(), second.getPrevHash());
        assertNotEquals(first.getRowHash(), second.getRowHash());
        assertEquals(expectedHmac(auditIntegrityService.integrityPayload(first)), first.getRowHash());
        assertEquals(1, checkpointCount(first.getId()));
    }

    @Test
    void orgEventsChainByOrganizationScope() {
        int orgId = workspaceMapper.getOrgId(workspace.getId());
        String summary = "org-" + unique();
        String expectedPreviousHash = jdbcTemplate.query(
            "SELECT row_hash FROM audit_log"
                + " WHERE chain_scope_type = 'organization' AND chain_scope_id = ?"
                + " ORDER BY chain_index DESC LIMIT 1",
            (resultSet, rowNum) -> resultSet.getString("row_hash"),
            orgId).stream().findFirst().orElse(HASH_GENESIS);

        auditService.record("test.org_integrity", "organization", orgId, "Org", summary, null);

        AuditLog entry = findBySummary(auditService.recentForOrg(orgId, 20, 0), summary);

        assertEquals("organization", entry.getChainScopeType());
        assertEquals(orgId, entry.getChainScopeId());
        assertEquals(expectedPreviousHash, entry.getPrevHash());
        assertNotNull(entry.getRowHash());
        assertEquals(64, entry.getRowHash().length());
    }

    @Test
    void legacyReferenceContentIsRedactedWithoutRewritingTheStoredChain() {
        AuditLog legacy = new AuditLog();
        legacy.setWorkspaceId(workspace.getId());
        legacy.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        legacy.setAction("deal.update");
        legacy.setEntityType("deal");
        legacy.setEntityId(99);
        legacy.setTargetLabel("Deal [Private](note:4242)");
        legacy.setOutcome("success");
        legacy.setSummary("Updated [Private](note:4242)");
        legacy.setChanges("{\"closedReason\":{\"new\":\"See [Private](note:4242)\"}}");
        auditIntegrityService.append(legacy);

        AuditLog stored = findById(
            auditLogMapper.findRecent(workspace.getId(), 50, 0), legacy.getId());
        assertTrue(stored.getChanges().contains("Private"));
        assertEquals(expectedHmac(auditIntegrityService.integrityPayload(stored)), stored.getRowHash());

        AuditLog projected = findById(auditService.recent(50, 0), legacy.getId());
        assertFalse(projected.getTargetLabel().contains("Private"));
        assertFalse(projected.getSummary().contains("Private"));
        assertFalse(projected.getChanges().contains("Private"));
        assertEquals(stored.getRowHash(), projected.getRowHash());
        assertTrue(projected.isContentRedacted());
    }

    @Test
    void untrustedClientCorrelationStaysOutsideTheRollingCompatibleIntegrityPayload() {
        AuditLog withoutClientCorrelation = new AuditLog();
        AuditLog withClientCorrelation = new AuditLog();
        withClientCorrelation.setUntrustedClientAssertedCorrelationHmac(
            "client-correlation-hmac");

        assertEquals(
            auditIntegrityService.integrityPayload(withoutClientCorrelation),
            auditIntegrityService.integrityPayload(withClientCorrelation));
    }

    @Test
    void databaseSnapshotsIntegrityReferencesForRollingDeploymentWriters() {
        int orgId = workspaceMapper.getOrgId(workspace.getId());
        AuditLog entry = new AuditLog();
        entry.setWorkspaceId(workspace.getId());
        entry.setOrgId(orgId);
        entry.setActorId(currentUser.getId());
        entry.setAction("test.rolling_writer");
        entry.setEntityType("workspace");
        entry.setEntityId(workspace.getId());
        entry.setOutcome("success");
        entry.setChainScopeType("workspace");
        entry.setChainScopeId(workspace.getId());
        entry.setChainIndex(Math.max(10_000_000L, System.nanoTime()));
        entry.setPrevHash("d".repeat(64));
        entry.setRowHash("c".repeat(64));

        auditLogMapper.insert(entry);

        Map<String, Object> references = jdbcTemplate.queryForMap(
            "SELECT integrity_workspace_id, integrity_org_id, integrity_actor_id,"
                + " integrity_reference_state FROM audit_log WHERE id = ?",
            entry.getId());
        assertEquals(workspace.getId(), references.get("integrity_workspace_id"));
        assertEquals(orgId, references.get("integrity_org_id"));
        assertEquals(currentUser.getId(), references.get("integrity_actor_id"));
        assertEquals("captured", references.get("integrity_reference_state"));
    }

    @Test
    void auditLogStaysAppendOnlyOnTheMigratedSchema() {
        String summary = "append-only-" + unique();
        auditService.record("test.integrity.append_only", "company", 1, "Acme", summary, null);
        AuditLog entry = findBySummary(auditService.recent(20, 0), summary);

        RuntimeException failure = assertThrows(
            RuntimeException.class,
            () -> jdbcTemplate.update(
                "UPDATE audit_log SET summary = ? WHERE id = ?",
                "tampered",
                entry.getId()));

        assertEquals("45000", sqlState(failure));
        assertTrue(auditIntegrityMapper.appendOnlyGuardInstalled());
    }

    @Test
    void legacyUnknownReferencesAreNotReportedAsVerifiable() {
        AuditLog entry = new AuditLog();
        entry.setIntegrityReferenceState("legacy_unknown");
        entry.setRowHash("c".repeat(64));

        assertFalse(auditIntegrityService.hasValidIntegrity(entry));
    }

    private static String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    private int checkpointCount(int auditLogId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log_integrity_checkpoint WHERE audit_log_id = ?",
                Integer.class, auditLogId);
        return count == null ? 0 : count;
    }

    private static String expectedHmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static AuditLog findBySummary(List<AuditLog> entries, String summary) {
        return entries.stream()
            .filter(entry -> summary.equals(entry.getSummary()))
            .findFirst()
            .orElseThrow();
    }

    private static AuditLog findById(List<AuditLog> entries, int id) {
        return entries.stream()
            .filter(entry -> entry.getId() == id)
            .findFirst()
            .orElseThrow();
    }
}
