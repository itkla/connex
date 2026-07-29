package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.ActiveObjectReference;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.TenantExportService.TenantExportDownload;
import ooo.klae.connex.backend.services.TenantExportSnapshotTransaction.CapturedTable;
import ooo.klae.connex.backend.services.TenantExportSnapshotTransaction.Snapshot;
import ooo.klae.connex.backend.services.TenantLifecycleAccess.Route;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.AcquiredWorkspace;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.OperationLease;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedTenantObject;
import ooo.klae.connex.backend.storage.StoredObject;
import ooo.klae.connex.backend.tenant.TenantLifecycleProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class TenantExportServiceTest {
    private static final int ORG_ID = 3;
    private static final int WORKSPACE_ID = 5;
    private static final int ACTOR_ID = 7;
    private static final WorkspaceLifecycleRef WORKSPACE =
        new WorkspaceLifecycleRef(WORKSPACE_ID, ORG_ID, "Workspace", "workspace", "active");
    private static final Route ROUTE = new Route(ORG_ID, WORKSPACE_ID, "tenant_catalog");
    private static final OperationLease OPERATION_LEASE =
        new OperationLease(ORG_ID, WORKSPACE_ID, "export", "lease-token");

    @Mock private OrgMemberService orgMemberService;
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private TenantLifecycleControlOperations controlOperations;
    @Mock private TenantLifecycleAccess lifecycleAccess;
    @Mock private TenantExportSnapshotTransaction snapshotTransaction;
    @Mock private ManagedObjectService managedObjectService;
    @Mock private AuditService auditService;

    private final TenantLifecycleProperties properties = new TenantLifecycleProperties();
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private TenantExportService service;

    @BeforeEach
    void setUp() {
        service = new TenantExportService(
            orgMemberService,
            sessionSecurityService,
            tenantWorkScope,
            controlOperations,
            lifecycleAccess,
            snapshotTransaction,
            managedObjectService,
            auditService,
            properties,
            objectMapper,
            Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC));
        lenient().doAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get())
            .when(tenantWorkScope).unrouted(any());
    }

    @AfterEach
    void tearDown() {
        service.shutdownDeadlineExecutor();
    }

    @Test
    void acquiresDatabaseGlobalAdmissionAfterPreliminaryAuthorization() {
        stubPreparedRoute();
        TenantExportDownload download = service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);

        InOrder order = inOrder(
            orgMemberService,
            sessionSecurityService,
            controlOperations);
        order.verify(orgMemberService).requireOrgAdmin(ORG_ID, ACTOR_ID);
        order.verify(sessionSecurityService).requireRecentAuthentication(ACTOR_ID);
        order.verify(controlOperations).acquireExport(ORG_ID, WORKSPACE_ID, ACTOR_ID);
        download.cancel();
    }

    @Test
    void cancellationExecutorMatchesTheFourExportCapacityInvariant() throws Exception {
        Field field = TenantExportService.class.getDeclaredField("cancellationExecutor");
        field.setAccessible(true);
        Object value = field.get(service);
        if (!(value instanceof ThreadPoolExecutor executor)) {
            throw new AssertionError("Tenant export cancellation executor is unavailable");
        }

        assertEquals(TenantExportService.MAX_CONCURRENT_EXPORTS, executor.getCorePoolSize());
        assertEquals(TenantExportService.MAX_CONCURRENT_EXPORTS, executor.getMaximumPoolSize());
        assertTrue(executor.getQueue() instanceof ArrayBlockingQueue<?>);
        assertEquals(
            TenantExportService.MAX_CONCURRENT_EXPORTS,
            executor.getQueue().remainingCapacity());
    }

    @Test
    void auditFailurePreservesThePrimaryAndSuppressesCleanupFailure() {
        stubPreparedRoute();
        IllegalStateException primary = new IllegalStateException("audit failed");
        IllegalStateException cleanup = new IllegalStateException("release failed");
        doThrow(primary).when(auditService).recordStrictIndependentScoped(
            any(),
            any(),
            anyInt(),
            any(),
            anyInt(),
            any(),
            any(),
            any());
        doThrow(cleanup).when(controlOperations).release(OPERATION_LEASE);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID));

        assertSame(primary, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanup, thrown.getSuppressed()[0]);
        verify(tenantWorkScope, times(2)).unrouted(any());
    }

    @Test
    void databaseAdmissionFailurePreservesThePrimaryWithoutAttemptingRelease() {
        IllegalStateException primary = new IllegalStateException("lease failed");
        when(controlOperations.acquireExport(ORG_ID, WORKSPACE_ID, ACTOR_ID))
            .thenThrow(primary);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID));

        assertSame(primary, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void repeatedNewCancellationReleasesTheExactLeaseOnce() {
        stubPreparedRoute();
        TenantExportDownload download = service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);

        download.cancel();
        download.cancel();

        verify(controlOperations, timeout(1_000).times(1)).release(OPERATION_LEASE);
        verify(tenantWorkScope, timeout(1_000).times(2)).unrouted(any());
    }

    @Test
    void deadlineScheduleRejectionCleansTheSpoolBeforeReleasingTheLease() {
        stubPreparedRoute();
        service.shutdownDeadlineExecutor();

        assertThrows(
            java.util.concurrent.RejectedExecutionException.class,
            () -> service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID));

        verify(controlOperations).release(OPERATION_LEASE);
        verify(tenantWorkScope, times(2)).unrouted(any());
    }

    @Test
    void capturedMissingObjectFailsHardAndStillReleasesEveryResource() throws Exception {
        stubStreamingRoute();
        AtomicReference<Path> capturedSpool = new AtomicReference<>();
        ActiveObjectReference reference = new ActiveObjectReference(
            "workspaces/5/attachments/file.pdf",
            "attachment",
            0,
            "/api/attachments/content/file.pdf",
            4L);
        doAnswer(invocation -> {
            Path spool = invocation.getArgument(2);
            capturedSpool.set(spool);
            Files.writeString(
                spool,
                objectMapper.writeValueAsString(reference) + "\n",
                StandardCharsets.UTF_8);
            return new Snapshot(List.of(), 1);
        }).when(snapshotTransaction).capture(
            eq(WORKSPACE_ID),
            any(),
            any(Path.class),
            any(TenantExportExecution.class));
        when(managedObjectService.openTenantExportObject(
                eq(WORKSPACE_ID),
                eq(ACTOR_ID),
                eq(reference),
                any()))
            .thenThrow(new ResourceNotFoundException("Stored file was not found"));
        TenantExportDownload download = service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);

        assertThrows(
            ResourceNotFoundException.class,
            () -> download.writeTo(new ByteArrayOutputStream()));

        InOrder order = inOrder(snapshotTransaction, managedObjectService);
        order.verify(snapshotTransaction).capture(
            eq(WORKSPACE_ID),
            any(),
            any(Path.class),
            any(TenantExportExecution.class));
        order.verify(managedObjectService).openTenantExportObject(
            eq(WORKSPACE_ID),
            eq(ACTOR_ID),
            eq(reference),
            any());
        verify(controlOperations).release(OPERATION_LEASE);
        verify(tenantWorkScope, times(2)).unrouted(any());
        assertFalse(Files.exists(capturedSpool.get()));
    }

    @Test
    void successfulZipCloseReleasesOutputOwnershipAfterABodyFailure() throws Exception {
        stubStreamingRoute();
        IOException primary = new IOException("snapshot failed");
        doThrow(primary).when(snapshotTransaction).capture(
            eq(WORKSPACE_ID),
            any(),
            any(Path.class),
            any(TenantExportExecution.class));
        TenantExportDownload download = service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);
        SingleCloseOutputStream output = new SingleCloseOutputStream();

        assertSame(primary, assertThrows(IOException.class, () -> download.writeTo(output)));

        assertEquals(1, output.closeCount());
        verify(controlOperations).release(OPERATION_LEASE);
    }

    @Test
    void writerAndCancellationShareOneOutputCloseClaimAfterSnapshotHandoff()
            throws Exception {
        stubStreamingRoute();
        CountDownLatch captureReady = new CountDownLatch(1);
        CountDownLatch releaseCapture = new CountDownLatch(1);
        CountDownLatch blockerCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseBlockerClose = new CountDownLatch(1);
        doAnswer(invocation -> {
            TenantExportExecution execution = invocation.getArgument(3);
            execution.track(() -> {
                blockerCloseEntered.countDown();
                if (!releaseBlockerClose.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("Cancellation blocker was not released");
                }
            });
            captureReady.countDown();
            if (!releaseCapture.await(2, TimeUnit.SECONDS)) {
                throw new IOException("Snapshot capture was not released");
            }
            return new Snapshot(List.of(), 0);
        }).when(snapshotTransaction).capture(
            eq(WORKSPACE_ID),
            any(),
            any(Path.class),
            any(TenantExportExecution.class));
        TenantExportDownload download = service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);
        SingleCloseOutputStream output = new SingleCloseOutputStream();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> writer = executor.submit(() ->
                assertThrows(IOException.class, () -> download.writeTo(output)));
            assertTrue(captureReady.await(2, TimeUnit.SECONDS));
            download.cancel();
            assertTrue(blockerCloseEntered.await(2, TimeUnit.SECONDS));
            releaseCapture.countDown();
            output.awaitClosed();
            releaseBlockerClose.countDown();
            writer.get(2, TimeUnit.SECONDS);
        } finally {
            releaseCapture.countDown();
            releaseBlockerClose.countDown();
        }

        assertEquals(1, output.closeCount());
        verify(controlOperations).release(OPERATION_LEASE);
    }

    @Test
    void successfulManifestPreservesTheVersionOnePublicFields() throws Exception {
        stubStreamingRoute();
        AtomicReference<Path> capturedSpool = new AtomicReference<>();
        doAnswer(invocation -> {
            Path spool = invocation.getArgument(2);
            capturedSpool.set(spool);
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(spool));
            ZipOutputStream zip = invocation.getArgument(1);
            zip.putNextEntry(new ZipEntry("data/person.jsonl"));
            zip.write("{}\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            return new Snapshot(
                List.of(new CapturedTable("person", "data/person.jsonl", 1)),
                0);
        }).when(snapshotTransaction).capture(
            eq(WORKSPACE_ID),
            any(),
            any(Path.class),
            any(TenantExportExecution.class));
        TenantExportDownload download = service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        download.writeTo(output);

        JsonNode manifest = manifest(output.toByteArray());
        assertEquals(7, manifest.size());
        for (String field : List.of(
                "schemaVersion",
                "generatedAt",
                "organizationId",
                "workspaceId",
                "format",
                "tables",
                "objectCount")) {
            assertTrue(manifest.has(field), field);
        }
        assertFalse(manifest.has("enumeratedObjectCount"));
        assertFalse(manifest.has("skippedObjectCount"));
        assertEquals(
            List.of("data/person.jsonl", "manifest.json"),
            entryNames(output.toByteArray()));
        verify(controlOperations).release(OPERATION_LEASE);
        verify(tenantWorkScope, times(2)).unrouted(any());
        assertFalse(Files.exists(capturedSpool.get()));
    }

    @Test
    void deadlineCancellationReleasesTheLeaseThroughUnroutedScope() {
        properties.setExportTimeout(Duration.ofMillis(30));
        stubPreparedRoute();

        service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);

        verify(controlOperations, timeout(1_000)).release(OPERATION_LEASE);
        verify(tenantWorkScope, timeout(1_000).times(2)).unrouted(any());
    }

    @Test
    void spoolDeletionFailureRetainsTheExactLease() throws Exception {
        stubStreamingRoute();
        AtomicReference<Path> capturedSpool = new AtomicReference<>();
        AtomicReference<Path> blocker = new AtomicReference<>();
        doAnswer(invocation -> {
            Path spool = invocation.getArgument(2);
            capturedSpool.set(spool);
            Files.delete(spool);
            Files.createDirectory(spool);
            blocker.set(Files.createFile(spool.resolve("retained")));
            return new Snapshot(List.of(), 0);
        }).when(snapshotTransaction).capture(
            eq(WORKSPACE_ID),
            any(),
            any(Path.class),
            any(TenantExportExecution.class));
        TenantExportDownload download = service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);

        try {
            assertThrows(
                IOException.class,
                () -> download.writeTo(new ByteArrayOutputStream()));

            verify(controlOperations, never()).release(OPERATION_LEASE);
            assertTrue(Files.exists(capturedSpool.get()));
        } finally {
            Files.deleteIfExists(blocker.get());
            Files.deleteIfExists(capturedSpool.get());
        }
    }

    @Test
    void writingCancellationClosesBlockedOutputBeforeLeaseReleaseAndWritesNoManifest()
            throws Exception {
        stubStreamingRoute();
        AtomicBoolean manifestWritten = new AtomicBoolean();
        doAnswer(invocation -> {
            ZipOutputStream zip = invocation.getArgument(1);
            zip.putNextEntry(new ZipEntry("data/person.jsonl"));
            zip.write("{}\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            manifestWritten.set(true);
            return new Snapshot(List.of(), 0);
        }).when(snapshotTransaction).capture(
            eq(WORKSPACE_ID),
            any(),
            any(Path.class),
            any(TenantExportExecution.class));
        BlockingOutputStream output = new BlockingOutputStream();
        AtomicBoolean releasedAfterClose = new AtomicBoolean();
        doAnswer(invocation -> {
            releasedAfterClose.set(output.closed());
            return null;
        }).when(controlOperations).release(OPERATION_LEASE);
        TenantExportDownload download = service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> writer = executor.submit(() ->
                assertThrows(IOException.class, () -> download.writeTo(output)));
            output.awaitWrite();

            download.cancel();
            writer.get(2, TimeUnit.SECONDS);
        }

        assertTrue(output.closed());
        assertTrue(releasedAfterClose.get());
        assertFalse(manifestWritten.get());
    }

    @Test
    void writingCancellationClosesBlockedProviderBeforeLeaseReleaseAndWritesNoManifest()
            throws Exception {
        stubStreamingRoute();
        ActiveObjectReference reference = new ActiveObjectReference(
            "workspaces/5/attachments/file.pdf",
            "attachment",
            0,
            "/api/attachments/content/file.pdf",
            4L);
        doAnswer(invocation -> {
            Path spool = invocation.getArgument(2);
            Files.writeString(
                spool,
                objectMapper.writeValueAsString(reference) + "\n",
                StandardCharsets.UTF_8);
            return new Snapshot(List.of(), 1);
        }).when(snapshotTransaction).capture(
            eq(WORKSPACE_ID),
            any(),
            any(Path.class),
            any(TenantExportExecution.class));
        BlockingInputStream input = new BlockingInputStream();
        ManagedTenantObject object = new ManagedTenantObject(
            reference.objectKey(),
            new StoredObject(input, 4),
            4);
        when(managedObjectService.openTenantExportObject(
                eq(WORKSPACE_ID),
                eq(ACTOR_ID),
                eq(reference),
                any()))
            .thenReturn(object);
        AtomicBoolean releasedAfterClose = new AtomicBoolean();
        doAnswer(invocation -> {
            releasedAfterClose.set(input.closed());
            return null;
        }).when(controlOperations).release(OPERATION_LEASE);
        TenantExportDownload download = service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> writer = executor.submit(() ->
                assertThrows(IOException.class, () -> download.writeTo(output)));
            input.awaitRead();

            download.cancel();
            writer.get(2, TimeUnit.SECONDS);
        }

        assertTrue(input.closed());
        assertTrue(releasedAfterClose.get());
        assertFalse(entryNames(output.toByteArray()).contains("manifest.json"));
    }

    @Test
    void objectAdmissionRetryUsesOneBoundedDeadlineWithoutResetting() throws Exception {
        properties.setExportObjectReadTimeout(Duration.ofMillis(45));
        stubStreamingRoute();
        ActiveObjectReference reference = reference("retry.pdf", 1);
        doAnswer(invocation -> {
            Path spool = invocation.getArgument(2);
            Files.writeString(
                spool,
                objectMapper.writeValueAsString(reference) + "\n",
                StandardCharsets.UTF_8);
            return new Snapshot(List.of(), 1);
        }).when(snapshotTransaction).capture(
            eq(WORKSPACE_ID),
            any(),
            any(Path.class),
            any(TenantExportExecution.class));
        when(managedObjectService.openTenantExportObject(
                eq(WORKSPACE_ID),
                eq(ACTOR_ID),
                eq(reference),
                any()))
            .thenThrow(new TooManyRequestsException("busy"));
        TenantExportDownload download = service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);

        assertThrows(
            TooManyRequestsException.class,
            () -> download.writeTo(new ByteArrayOutputStream()));

        ArgumentCaptor<Duration> timeouts = ArgumentCaptor.forClass(Duration.class);
        verify(managedObjectService, atLeast(2)).openTenantExportObject(
            eq(WORKSPACE_ID),
            eq(ACTOR_ID),
            eq(reference),
            timeouts.capture());
        List<Duration> captured = timeouts.getAllValues();
        assertTrue(captured.getLast().compareTo(captured.getFirst()) < 0);
    }

    @Test
    void eachObjectReceivesAnIndependentReadDeadline() throws Exception {
        properties.setExportObjectReadTimeout(Duration.ofMillis(150));
        stubStreamingRoute();
        ActiveObjectReference first = reference("first.pdf", 1);
        ActiveObjectReference second = reference("second.pdf", 1);
        doAnswer(invocation -> {
            Path spool = invocation.getArgument(2);
            Files.writeString(
                spool,
                objectMapper.writeValueAsString(first) + "\n"
                    + objectMapper.writeValueAsString(second) + "\n",
                StandardCharsets.UTF_8);
            return new Snapshot(List.of(), 2);
        }).when(snapshotTransaction).capture(
            eq(WORKSPACE_ID),
            any(),
            any(Path.class),
            any(TenantExportExecution.class));
        List<Duration> timeouts = new ArrayList<>();
        doAnswer(invocation -> {
            ActiveObjectReference current = invocation.getArgument(2);
            Duration timeout = invocation.getArgument(3);
            timeouts.add(timeout);
            if (current.equals(first)) {
                Thread.sleep(40);
            }
            return new ManagedTenantObject(
                current.objectKey(),
                new StoredObject(new ByteArrayInputStream(new byte[] {1}), 1),
                1);
        }).when(managedObjectService).openTenantExportObject(
            eq(WORKSPACE_ID),
            eq(ACTOR_ID),
            any(),
            any());
        TenantExportDownload download = service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);

        download.writeTo(new ByteArrayOutputStream());

        assertEquals(2, timeouts.size());
        assertTrue(timeouts.get(0).toMillis() >= 100);
        assertTrue(timeouts.get(1).toMillis() >= 100);
    }

    @Test
    void surplusObjectBytesNeverEnterTheExportEntry() throws Exception {
        ActiveObjectReference reference =
            stubSingleObject("surplus.bin", 3, new byte[] {1, 2, 3, 99, 100});
        TenantExportDownload download = service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThrows(IOException.class, () -> download.writeTo(output));

        assertArrayEquals(
            new byte[] {1, 2, 3},
            entryBytes(output.toByteArray(), "objects/" + reference.objectKey()));
        assertFalse(entryNames(output.toByteArray()).contains("manifest.json"));
    }

    @Test
    void shortObjectStreamFailsBeforeWritingAManifest() throws Exception {
        ActiveObjectReference reference =
            stubSingleObject("short.bin", 3, new byte[] {1, 2});
        TenantExportDownload download = service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThrows(IOException.class, () -> download.writeTo(output));

        assertArrayEquals(
            new byte[] {1, 2},
            entryBytes(output.toByteArray(), "objects/" + reference.objectKey()));
        assertFalse(entryNames(output.toByteArray()).contains("manifest.json"));
    }

    @Test
    void exactObjectStreamWritesTheEntryAndManifest() throws Exception {
        ActiveObjectReference reference =
            stubSingleObject("exact.bin", 3, new byte[] {1, 2, 3});
        TenantExportDownload download = service.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        download.writeTo(output);

        assertArrayEquals(
            new byte[] {1, 2, 3},
            entryBytes(output.toByteArray(), "objects/" + reference.objectKey()));
        assertTrue(entryNames(output.toByteArray()).contains("manifest.json"));
    }

    private void stubPreparedRoute() {
        when(controlOperations.acquireExport(ORG_ID, WORKSPACE_ID, ACTOR_ID))
            .thenReturn(new AcquiredWorkspace(WORKSPACE, OPERATION_LEASE));
        when(lifecycleAccess.capture(WORKSPACE, ORG_ID)).thenReturn(ROUTE);
    }

    private void stubStreamingRoute() {
        stubPreparedRoute();
        doAnswer(invocation -> invocation.<Supplier<?>>getArgument(2).get())
            .when(lifecycleAccess).withRoute(eq(ROUTE), eq(ACTOR_ID), any());
    }

    private static ActiveObjectReference reference(String name, long length) {
        return new ActiveObjectReference(
            "workspaces/5/attachments/" + name,
            "attachment",
            0,
            "/api/attachments/content/" + name,
            length);
    }

    private ActiveObjectReference stubSingleObject(
            String name,
            long expectedLength,
            byte[] bytes) throws Exception {
        stubStreamingRoute();
        ActiveObjectReference reference = reference(name, expectedLength);
        doAnswer(invocation -> {
            Path spool = invocation.getArgument(2);
            Files.writeString(
                spool,
                objectMapper.writeValueAsString(reference) + "\n",
                StandardCharsets.UTF_8);
            return new Snapshot(List.of(), 1);
        }).when(snapshotTransaction).capture(
            eq(WORKSPACE_ID),
            any(),
            any(Path.class),
            any(TenantExportExecution.class));
        ManagedTenantObject object = new ManagedTenantObject(
            reference.objectKey(),
            new StoredObject(new ByteArrayInputStream(bytes), expectedLength),
            expectedLength);
        when(managedObjectService.openTenantExportObject(
                eq(WORKSPACE_ID),
                eq(ACTOR_ID),
                eq(reference),
                any()))
            .thenReturn(object);
        return reference;
    }

    private JsonNode manifest(byte[] zipBytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("manifest.json".equals(entry.getName())) {
                    return objectMapper.readTree(zip.readAllBytes());
                }
            }
        }
        throw new AssertionError("Manifest entry was not written");
    }

    private List<String> entryNames(byte[] zipBytes) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return List.copyOf(names);
    }

    private byte[] entryBytes(byte[] zipBytes, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new AssertionError("ZIP entry was not written: " + name);
    }

    private static final class BlockingOutputStream extends OutputStream {
        private final CountDownLatch writeEntered = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void write(int value) throws IOException {
            writeEntered.countDown();
            awaitClosed();
            throw new IOException("Output closed");
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            writeEntered.countDown();
            awaitClosed();
            throw new IOException("Output closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }

        private void awaitWrite() throws InterruptedException {
            if (!writeEntered.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Export did not block on output");
            }
        }

        private void awaitClosed() throws IOException {
            try {
                if (!closed.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("Output was not closed by cancellation");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Output wait was interrupted", exception);
            }
        }

        private boolean closed() {
            return closed.getCount() == 0;
        }
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            readEntered.countDown();
            awaitClosed();
            throw new IOException("Provider stream closed");
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return read();
        }

        @Override
        public void close() {
            closed.countDown();
        }

        private void awaitRead() throws InterruptedException {
            if (!readEntered.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Export did not block on provider input");
            }
        }

        private void awaitClosed() throws IOException {
            try {
                if (!closed.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("Provider input was not closed by cancellation");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Provider wait was interrupted", exception);
            }
        }

        private boolean closed() {
            return closed.getCount() == 0;
        }
    }

    private static final class SingleCloseOutputStream extends ByteArrayOutputStream {
        private final CountDownLatch closed = new CountDownLatch(1);
        private int closeCount;

        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount > 1) {
                throw new IOException("Output closed more than once");
            }
            super.close();
            closed.countDown();
        }

        private void awaitClosed() throws InterruptedException {
            if (!closed.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Export output was not closed");
            }
        }

        private int closeCount() {
            return closeCount;
        }
    }
}
