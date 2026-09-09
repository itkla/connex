package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ActiveObjectReference;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.AttachmentScanMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.storage.AttachmentScanTransactions;
import ooo.klae.connex.backend.storage.AttachmentScanWorker;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ObjectStorage;
import ooo.klae.connex.backend.storage.UploadSource;
import ooo.klae.connex.backend.storage.malware.MalwareScanReport;
import ooo.klae.connex.backend.storage.malware.MalwareScannerClient;
import ooo.klae.connex.backend.storage.malware.MalwareScanVerdict;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Proves persisted denial, retained-object decisions and crash/concurrency semantics against MySQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "connex.malware-scan.sweep-delay-ms=3600000")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttachmentScanLifecycleIntegrationTest {
    private static final byte[] CONTENT = "retained attachment fixture".getBytes(StandardCharsets.UTF_8);

    @Autowired private OrganizationMapper organizations;
    @Autowired private WorkspaceMapper workspaces;
    @Autowired private UserMapper users;
    @Autowired private AttachmentMapper attachments;
    @MockitoSpyBean private AttachmentScanMapper scans;
    @Autowired private AttachmentScanTransactions transactions;
    @Autowired private AttachmentScanWorker worker;
    @Autowired private ManagedObjectService managedObjects;
    @Autowired private ObjectStorage storage;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @MockitoBean private MalwareScannerClient scanner;

    private final List<Workspace> fixtureWorkspaces = new ArrayList<>();
    private final List<User> fixtureUsers = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();
    private Organization organization;
    private Workspace workspace;
    private User firstActor;
    private User secondActor;

    @BeforeEach
    void setUp() {
        clearContext();
        organization = new Organization();
        organization.setName("Attachment lifecycle " + unique());
        organization.setSlug("attachment-lifecycle-" + unique());
        organizations.insert(organization);
        workspace = workspace();
        firstActor = actor(workspace);
        secondActor = actor(workspace);
        when(scanner.scan(any(byte[].class))).thenReturn(report(MalwareScanVerdict.CLEAN));
    }

    @AfterEach
    void tearDown() {
        clearContext();
        for (String key : objectKeys) {
            storage.delete(key);
        }
        for (Workspace fixture : fixtureWorkspaces.reversed()) {
            jdbc.update("DELETE FROM attachment WHERE workspace_id = ?", fixture.getId());
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", fixture.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", fixture.getId());
        }
        for (User fixture : fixtureUsers) {
            jdbc.update("DELETE FROM app_user WHERE id = ?", fixture.getId());
        }
        if (organization != null) {
            jdbc.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Autowired private ooo.klae.connex.backend.storage.malware.MalwareScanProperties scanProperties;
    @Autowired private ooo.klae.connex.backend.config.DeploymentProperties deploymentProperties;

    @Test
    void workspaceDiscoveryExaminesAtMostOneIndexEntryPerSeek() {
        for (int i = 0; i < 100; i++) {
            reference(workspace, "/api/attachments/content/" + UUID.randomUUID() + ".txt");
        }
        var parameters = java.util.Map.of("afterId", 0, "throughId", workspace.getId());
        var statement = sqlSessionTemplate.getConfiguration().getMappedStatement(
            "ooo.klae.connex.backend.mappers.AttachmentScanMapper.nextWorkspaceId");
        String sql = statement.getBoundSql(parameters).getSql();
        String plan = jdbc.queryForObject("EXPLAIN ANALYZE " + sql, String.class, 0, workspace.getId());
        assertNotNull(plan);
        System.out.println("WORKSPACE_DISCOVERY_EXPLAIN\n" + plan);
        var access = java.util.regex.Pattern.compile(
            "(?m)^.*(?:scan|lookup) on attachment .*actual time=[^\n]*?rows=([0-9]+)").matcher(plan);
        assertTrue(access.find(), "Expected measured attachment access iterator");
        assertTrue(Integer.parseInt(access.group(1)) <= 1, "Workspace seek must read at most one entry");
    }

    @Test
    void disabledProofRemainsReadableAfterExpiryOnlyWhileScanningIsDisabled() throws Exception {
        Attachment attachment = legacyAttachment(workspace);
        scan(attachment);
        jdbc.update("UPDATE attachment SET scan_database_version = 'disabled',"
            + " scan_expires_at = '2000-01-01', scanned_at = '1999-12-31' WHERE id = ?", attachment.getId());
        boolean enabled = scanProperties.isEnabled();
        String profile = deploymentProperties.getProfile();
        try {
            scanProperties.setEnabled(false);
            assertReadable(attachment);
            scanProperties.setEnabled(true);
            assertDenied(attachment);
            jdbc.update("UPDATE attachment SET scan_expires_at = '2099-01-01' WHERE id = ?", attachment.getId());
            assertDenied(attachment);
            scanProperties.setEnabled(false);
            assertReadable(attachment);
            deploymentProperties.setProfile("on-prem");
            assertDenied(attachment);
            deploymentProperties.setProfile(profile);
            scans.quarantine(workspace.getId(), attachment.getId());
            assertDenied(attachment);
        } finally {
            scanProperties.setEnabled(enabled);
            deploymentProperties.setProfile(profile);
        }
    }

    @Test
    void decisionAuditsSurvivingReferenceAfterClaimedReferenceIsDeletedDuringScan() throws Exception {
        Attachment claimed = legacyAttachment(workspace);
        Attachment survivor = reference(workspace, claimed.getUrl());
        when(scanner.scan(any(byte[].class))).thenAnswer(invocation -> {
            attachments.delete(workspace.getId(), claimed.getId());
            return report(MalwareScanVerdict.CLEAN);
        });
        assertTrue(scan(claimed));
        assertNull(scans.getById(workspace.getId(), claimed.getId()));
        assertReadable(survivor);
        assertEquals(1, decisions(survivor));
        assertEquals(0, decisions(claimed));
    }

    @Test
    void legacyBytesCannotBeReadOrExportedUntilAnExplicitCleanDecision() throws Exception {
        Attachment attachment = legacyAttachment(workspace);
        assertDenied(attachment);

        assertTrue(inContext(firstActor, workspace,
            () -> worker.scan(workspace.getId(), attachment.getId(), firstActor.getId())));

        Attachment decided = scans.getById(workspace.getId(), attachment.getId());
        assertEquals("clean", decided.getScanState());
        assertEquals("ClamAV", decided.getScanEngine());
        assertEquals("daily-fixture-42", decided.getScanDatabaseVersion());
        assertNotNull(decided.getScannedAt());
        assertNotNull(decided.getScanExpiresAt());
        assertEquals(1, decided.getScanAttempts());
        assertReadable(attachment);
        assertEquals(1, decisions(attachment));
    }

    @Test
    void infectedRescanRetainsAlreadyStoredBytesButBlocksBothReadBoundaries() throws Exception {
        Attachment attachment = legacyAttachment(workspace);
        scan(attachment);
        when(scanner.scan(any(byte[].class))).thenReturn(report(MalwareScanVerdict.INFECTED));
        scans.enqueue(workspace.getId(), attachment.getId());

        assertTrue(scan(attachment));

        assertEquals("infected", scans.getById(workspace.getId(), attachment.getId()).getScanState());
        assertDenied(attachment);
        try (var stored = storage.get(key(attachment))) {
            assertArrayEquals(CONTENT, stored.inputStream().readAllBytes());
        }
        assertEquals(2, decisions(attachment));
    }

    @Test
    void boundedBackfillResumesAfterClaimCrashWithoutReprocessingCommittedDecisions() throws Exception {
        Attachment first = legacyAttachment(workspace);
        Attachment second = legacyAttachment(workspace);
        inContext(firstActor, workspace, () -> {
            worker.sweepWorkspace(workspace.getId(), firstActor.getId(), 1);
            return null;
        });
        assertEquals(1, decisions(first));
        assertEquals(0, decisions(second));
        Attachment crashedClaim = inContext(firstActor, workspace,
            () -> transactions.claim(workspace.getId(), second.getId()));
        assertNotNull(crashedClaim);
        assertDenied(second);
        jdbc.update("UPDATE attachment SET scan_lease_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND)"
                + " WHERE workspace_id = ? AND id = ?", workspace.getId(), second.getId());

        inContext(secondActor, workspace, () -> {
            worker.sweepWorkspace(workspace.getId(), secondActor.getId(), 1);
            worker.sweepWorkspace(workspace.getId(), secondActor.getId(), 1);
            return null;
        });

        assertFalse(inContext(firstActor, workspace,
            () -> transactions.decide(crashedClaim, report(MalwareScanVerdict.INFECTED))));
        assertReadable(first);
        assertReadable(second);
        assertEquals(1, decisions(first));
        assertEquals(1, decisions(second));
        assertEquals(2, scans.getById(workspace.getId(), second.getId()).getScanAttempts());
        verify(scanner, times(2)).scan(any(byte[].class));
    }

    @Test
    void concurrentDistinctWorkersScanAndDecideOneSharedObjectExactlyOnce() throws Exception {
        Attachment first = legacyAttachment(workspace);
        Attachment sibling = reference(workspace, first.getUrl());
        CountDownLatch scannerEntered = new CountDownLatch(1);
        CountDownLatch releaseScanner = new CountDownLatch(1);
        AtomicInteger scannerCalls = new AtomicInteger();
        when(scanner.scan(any(byte[].class))).thenAnswer(invocation -> {
            if (scannerCalls.incrementAndGet() > 1) {
                return report(MalwareScanVerdict.CLEAN);
            }
            scannerEntered.countDown();
            if (!releaseScanner.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent fixture scanner release timed out");
            }
            return report(MalwareScanVerdict.CLEAN);
        });

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> firstResult = executor.submit(() -> inContext(firstActor, workspace,
                () -> worker.scan(workspace.getId(), first.getId(), firstActor.getId())));
            assertTrue(scannerEntered.await(5, TimeUnit.SECONDS));
            try {
                Future<Boolean> duplicate = executor.submit(() -> inContext(secondActor, workspace,
                    () -> worker.scan(workspace.getId(), sibling.getId(), secondActor.getId())));
                assertFalse(duplicate.get(5, TimeUnit.SECONDS));
            } finally {
                releaseScanner.countDown();
            }
            assertTrue(firstResult.get(5, TimeUnit.SECONDS));
        } finally {
            releaseScanner.countDown();
        }

        verify(scanner, times(1)).scan(any(byte[].class));
        assertEquals(1, decisions(first) + decisions(sibling));
        assertEquals(1, scans.getById(workspace.getId(), first.getId()).getScanAttempts());
        assertEquals(1, scans.getById(workspace.getId(), sibling.getId()).getScanAttempts());
        assertReadable(first);
        assertReadable(sibling);
    }

    @Test
    void quarantineDuringAnInFlightScanInvalidatesItsDecision() throws Exception {
        Attachment attachment = legacyAttachment(workspace);
        Attachment claim = inContext(firstActor, workspace,
            () -> transactions.claim(workspace.getId(), attachment.getId()));
        assertNotNull(claim);
        scans.quarantine(workspace.getId(), attachment.getId());

        assertFalse(inContext(firstActor, workspace,
            () -> transactions.decide(claim, report(MalwareScanVerdict.CLEAN))));

        assertEquals("quarantined", scans.getById(workspace.getId(), attachment.getId()).getScanState());
        assertTrue(scans.findDue(workspace.getId(), 1).isEmpty());
        assertDenied(attachment);
        assertEquals(0, decisions(attachment));
    }

    @Test
    void simultaneousPreLockSnapshotsCannotCreateTwoClaims() throws Exception {
        Attachment attachment = legacyAttachment(workspace);
        CountDownLatch discovered = new CountDownLatch(2);
        AttachmentScanMapper realScans = sqlSessionTemplate.getMapper(AttachmentScanMapper.class);
        doAnswer(invocation -> {
            Attachment result = realScans.getById(workspace.getId(), attachment.getId());
            discovered.countDown();
            if (!discovered.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent discovery fixture timed out");
            }
            return result;
        }).when(scans).getById(workspace.getId(), attachment.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> inContext(firstActor, workspace,
                () -> worker.scan(workspace.getId(), attachment.getId(), firstActor.getId())));
            Future<Boolean> second = executor.submit(() -> inContext(secondActor, workspace,
                () -> worker.scan(workspace.getId(), attachment.getId(), secondActor.getId())));
            int completed = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, completed);
        }

        verify(scanner, times(1)).scan(any(byte[].class));
        assertEquals(1, decisions(attachment));
        assertReadable(attachment);
    }

    @Test
    void workspaceSeeksSkipBusyCorpusAndStopAtTheCapturedCycleCeiling() throws Exception {
        Attachment busyFirst = legacyAttachment(workspace);
        legacyAttachment(workspace);
        Workspace waiting = workspace();
        legacyAttachment(waiting);
        int ceiling = scans.lastWorkspaceId();
        assertEquals(workspace.getId(), scans.nextWorkspaceId(workspace.getId() - 1, ceiling));
        assertTrue(scan(busyFirst));
        assertEquals(waiting.getId(), scans.nextWorkspaceId(workspace.getId(), ceiling));
        Workspace arrival = workspace();
        legacyAttachment(arrival);
        assertNull(scans.nextWorkspaceId(waiting.getId(), ceiling));
        assertEquals(arrival.getId(), scans.nextWorkspaceId(waiting.getId(), scans.lastWorkspaceId()));
    }

    @Test
    void scannerErrorAndStaleCleanProofStayUnreadableAndErrorRetryIsPersisted() throws Exception {
        Attachment attachment = legacyAttachment(workspace);
        when(scanner.scan(any(byte[].class))).thenThrow(new ServiceUnavailableException("fixture outage"));

        assertFalse(scan(attachment));

        Attachment failed = scans.getById(workspace.getId(), attachment.getId());
        assertEquals("error", failed.getScanState());
        assertNotNull(failed.getScanNextAttemptAt());
        assertTrue(scans.findDue(workspace.getId(), 1).isEmpty());
        assertDenied(attachment);
        scans.enqueue(workspace.getId(), attachment.getId());
        when(scanner.scan(any(byte[].class))).thenReturn(report(MalwareScanVerdict.CLEAN));
        assertTrue(scan(attachment));
        jdbc.update("UPDATE attachment SET scan_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND),"
                + " scan_next_attempt_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND)"
                + " WHERE workspace_id = ? AND id = ?", workspace.getId(), attachment.getId());

        assertDenied(attachment);
        assertEquals(List.of(attachment.getId()), scans.findDue(workspace.getId(), 1));
    }

    @Test
    void expiredRenewalsCannotStarveAnOlderPendingRetryOrExplicitRescanBacklog() throws Exception {
        Attachment renewed = legacyAttachment(workspace);
        Attachment pending = legacyAttachment(workspace);
        Attachment retry = legacyAttachment(workspace);
        Attachment explicit = legacyAttachment(workspace);
        assertTrue(scan(renewed));
        when(scanner.scan(any(byte[].class))).thenThrow(new ServiceUnavailableException("fixture outage"));
        assertFalse(scan(retry));
        scans.enqueue(workspace.getId(), explicit.getId());
        jdbc.update("UPDATE attachment SET created_at = '2000-01-01' WHERE workspace_id = ?",
            workspace.getId());
        jdbc.update("UPDATE attachment SET scan_next_attempt_at = '2001-01-01'"
            + " WHERE workspace_id = ? AND id = ?", workspace.getId(), retry.getId());
        jdbc.update("UPDATE attachment SET scan_next_attempt_at = '2002-01-01'"
            + " WHERE workspace_id = ? AND id = ?", workspace.getId(), explicit.getId());
        jdbc.update("UPDATE attachment SET scan_expires_at = '2003-01-01', scan_next_attempt_at = NULL"
            + " WHERE workspace_id = ? AND id = ?", workspace.getId(), renewed.getId());
        AtomicInteger renewal = new AtomicInteger();
        doAnswer(invocation -> new MalwareScanReport(
            MalwareScanVerdict.CLEAN, null, null, "daily-fixture-42", false,
            Instant.parse("2004-01-01T00:00:00Z").plusSeconds(renewal.getAndIncrement())))
            .when(scanner).scan(any(byte[].class));

        List<Attachment> oldestFirst = List.of(pending, retry, explicit, renewed);
        for (int cycle = 0; cycle < 3; cycle++) {
            for (Attachment expected : oldestFirst) {
                assertEquals(List.of(expected.getId()), scans.findDue(workspace.getId(), 1),
                    "Expired renewals must yield to older due work, cycle " + cycle);
                inContext(firstActor, workspace, () -> {
                    worker.sweepWorkspace(workspace.getId(), firstActor.getId(), 1);
                    return null;
                });
                Attachment decided = scans.getById(workspace.getId(), expected.getId());
                assertEquals(decided.getScanExpiresAt(), decided.getScanNextAttemptAt());
            }
        }
        assertEquals(3, decisions(pending));
        assertEquals(3, decisions(retry));
        assertEquals(3, decisions(explicit));
        assertEquals(4, decisions(renewed));
    }

    @Test
    void everyScanMapperIdentityOperationIsScopedToItsWorkspace() throws Exception {
        Attachment attachment = legacyAttachment(workspace);
        Workspace foreign = workspace();
        User foreignActor = actor(foreign);

        inContext(foreignActor, foreign, () -> {
            assertNull(scans.getById(foreign.getId(), attachment.getId()));
            assertNull(scans.lockById(foreign.getId(), attachment.getId()));
            assertFalse(scans.isReadable(foreign.getId(), attachment.getUrl(), false));
            assertEquals(0, scans.quarantine(foreign.getId(), attachment.getId()));
            assertEquals(0, scans.enqueue(foreign.getId(), attachment.getId()));
            assertFalse(worker.scan(foreign.getId(), attachment.getId(), foreignActor.getId()));
            assertTrue(scans.findDue(foreign.getId(), 1).isEmpty());
            return null;
        });

        assertEquals("pending", scans.getById(workspace.getId(), attachment.getId()).getScanState());
        assertEquals(0, decisions(attachment));
    }

    private void assertDenied(Attachment attachment) {
        inContext(firstActor, workspace, () -> {
            assertThrows(ResourceNotFoundException.class,
                () -> managedObjects.openAttachment(attachment.getWorkspaceId(), attachment));
            ActiveObjectReference reference = new ActiveObjectReference(
                key(attachment), "attachment", 0, attachment.getUrl(), (long) CONTENT.length);
            assertThrows(ResourceNotFoundException.class,
                () -> managedObjects.openTenantExportObject(attachment.getWorkspaceId(), firstActor.getId(),
                    reference, Duration.ofSeconds(5)));
            return null;
        });
    }

    private void assertReadable(Attachment attachment) {
        inContext(firstActor, workspace, () -> {
            try (var content = managedObjects.openAttachment(attachment.getWorkspaceId(), attachment)) {
                assertArrayEquals(CONTENT, content.inputStream().readAllBytes());
            }
            ActiveObjectReference reference = new ActiveObjectReference(
                key(attachment), "attachment", 0, attachment.getUrl(), (long) CONTENT.length);
            try (var content = managedObjects.openTenantExportObject(
                    attachment.getWorkspaceId(), firstActor.getId(), reference, Duration.ofSeconds(5))) {
                assertArrayEquals(CONTENT, content.inputStream().readAllBytes());
            }
            return null;
        });
    }

    private boolean scan(Attachment attachment) {
        return inContext(firstActor, workspace,
            () -> worker.scan(workspace.getId(), attachment.getId(), firstActor.getId()));
    }

    private Attachment legacyAttachment(Workspace owner) throws Exception {
        Attachment attachment = reference(owner,
            "/api/attachments/content/" + UUID.randomUUID() + ".txt");
        String key = key(attachment);
        storage.put(key, UploadSource.from("retained.txt", "text/plain", CONTENT), "text/plain",
            MessageDigest.getInstance("SHA-256").digest(CONTENT));
        objectKeys.add(key);
        return attachment;
    }

    private Attachment reference(Workspace owner, String url) {
        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(owner.getId());
        attachment.setEntityType("user");
        attachment.setEntityId(firstActor.getId());
        attachment.setFileName("retained.txt");
        attachment.setUrl(url);
        attachment.setContentType("text/plain");
        attachment.setSize((long) CONTENT.length);
        attachments.insert(attachment);
        return attachment;
    }

    private String key(Attachment attachment) {
        return "workspaces/" + attachment.getWorkspaceId() + "/attachments/"
            + attachment.getUrl().substring("/api/attachments/content/".length());
    }

    private Workspace workspace() {
        Workspace created = new Workspace();
        created.setName("Attachment lifecycle " + unique());
        created.setSlug("attachment-lifecycle-" + unique());
        created.setOrgId(organization.getId());
        workspaces.insert(created);
        fixtureWorkspaces.add(created);
        return created;
    }

    private User actor(Workspace owner) {
        User actor = new User();
        actor.setUsername("attachment_lifecycle_" + unique());
        actor.setDisplayName("Attachment lifecycle fixture");
        actor.setEmail(unique() + "@example.com");
        actor.setPasswordHash("unusable-fixture-password-hash");
        actor.setTimezone("UTC");
        users.insert(actor);
        workspaces.addMember(owner.getId(), actor.getId(), "member");
        fixtureUsers.add(actor);
        return actor;
    }

    private int decisions(Attachment attachment) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND entity_id = ? AND action = 'malware.decided'",
            Integer.class, attachment.getWorkspaceId(), attachment.getId());
        assertNotNull(count);
        return count;
    }

    private MalwareScanReport report(MalwareScanVerdict verdict) {
        return new MalwareScanReport(verdict,
            verdict == MalwareScanVerdict.INFECTED ? "Fixture.Malware" : null,
            null, "daily-fixture-42", false, Instant.now().plusSeconds(3600));
    }

    private <T> T inContext(User actor, Workspace owner, Callable<T> action) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        tenantContext.set(owner.getId(), owner.getOrgId(), actor.getId(), "member", null);
        try {
            return action.call();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Attachment lifecycle fixture failed", exception);
        } finally {
            clearContext();
        }
    }

    private void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
