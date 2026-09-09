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
    void durableLastAttemptMovesBusyWorkspaceBehindAnUnservedWorkspace() throws Exception {
        Attachment busyFirst = legacyAttachment(workspace);
        legacyAttachment(workspace);
        Workspace waiting = workspace();
        legacyAttachment(waiting);
        List<Integer> initial = scans.workspaceIdsWithDueTasks(10000);
        assertTrue(initial.contains(workspace.getId()));
        assertTrue(initial.contains(waiting.getId()));
        assertTrue(initial.indexOf(workspace.getId()) < initial.indexOf(waiting.getId()));

        assertTrue(scan(busyFirst));

        List<Integer> resumed = scans.workspaceIdsWithDueTasks(10000);
        assertTrue(resumed.contains(workspace.getId()));
        assertTrue(resumed.contains(waiting.getId()));
        assertTrue(resumed.indexOf(waiting.getId()) < resumed.indexOf(workspace.getId()));
        assertEquals(resumed, scans.workspaceIdsWithDueTasks(10000));
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
        jdbc.update("UPDATE attachment SET scan_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND)"
                + " WHERE workspace_id = ? AND id = ?", workspace.getId(), attachment.getId());

        assertDenied(attachment);
        assertEquals(List.of(attachment.getId()), scans.findDue(workspace.getId(), 1));
    }

    @Test
    void everyScanMapperIdentityOperationIsScopedToItsWorkspace() throws Exception {
        Attachment attachment = legacyAttachment(workspace);
        Workspace foreign = workspace();
        User foreignActor = actor(foreign);

        inContext(foreignActor, foreign, () -> {
            assertNull(scans.getById(foreign.getId(), attachment.getId()));
            assertNull(scans.lockById(foreign.getId(), attachment.getId()));
            assertFalse(scans.isReadable(foreign.getId(), attachment.getUrl()));
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
