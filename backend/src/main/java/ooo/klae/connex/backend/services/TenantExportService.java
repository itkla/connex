package ooo.klae.connex.backend.services;

import java.io.BufferedReader;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ActiveObjectReference;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.TenantExportExecution.TrackedResource;
import ooo.klae.connex.backend.services.TenantExportSnapshotTransaction.CapturedTable;
import ooo.klae.connex.backend.services.TenantExportSnapshotTransaction.Snapshot;
import ooo.klae.connex.backend.services.TenantLifecycleAccess.Route;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.AcquiredWorkspace;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.OperationLease;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedTenantObject;
import ooo.klae.connex.backend.tenant.TenantLifecycleProperties;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.ControlWorkspaceLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/**
 * Authorizes and streams a complete point-in-time workspace export without materializing
 * tenant tables or object bytes in memory.
 *
 * <p>One repeatable-read transaction writes every registry table to the ZIP and captures
 * every active managed-object reference to a private spool. All database cursors and the
 * snapshot transaction close before provider I/O begins. Every captured object must still
 * match its canonical key, owner, persisted URL, usage ledger, and byte length or the export
 * fails without a manifest.
 *
 * <p>APPI-restricted people are included, together with {@code suspended_at} and
 * {@code provision_ceased_at}, because an offboarding export represents the organization's
 * complete lawful holdings. A failure after response streaming starts necessarily produces
 * a truncated ZIP; strict authorization audit is durable before any response body begins.
 */
@Service
@RequiredArgsConstructor
public class TenantExportService {
    private static final String AUDIT_ACTION = "org.workspace.export";
    static final int MAX_CONCURRENT_EXPORTS = 4;
    private static final long OBJECT_ADMISSION_RETRY_NANOS =
        Duration.ofMillis(10).toNanos();

    private final OrgMemberService orgMemberService;
    private final SessionSecurityService sessionSecurityService;
    private final TenantWorkScope tenantWorkScope;
    private final TenantLifecycleControlOperations controlOperations;
    private final TenantLifecycleAccess lifecycleAccess;
    private final TenantExportSnapshotTransaction snapshotTransaction;
    private final ManagedObjectService managedObjectService;
    private final AuditService auditService;
    private final TenantLifecycleProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor deadlineExecutor = deadlineExecutor();
    private final ThreadPoolExecutor cancellationExecutor = cancellationExecutor();

    /**
     * Performs synchronous authorization, database-global admission, spool creation, and strict
     * audit before returning a streaming writer.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TenantExportDownload prepare(int orgId, int workspaceId, int actorId) {
        return tenantWorkScope.unrouted(
            () -> prepareUnrouted(orgId, workspaceId, actorId));
    }

    private TenantExportDownload prepareUnrouted(
            int orgId,
            int workspaceId,
            int actorId) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        AcquiredWorkspace acquired = controlOperations.acquireExport(
            orgId,
            workspaceId,
            actorId);
        return prepareAcquired(orgId, actorId, acquired);
    }

    TenantExportDownload prepareAcquired(
            int orgId,
            int actorId,
            AcquiredWorkspace acquired) {
        OperationLease operationLease = acquired.lease();
        Path objectSpool = null;
        try {
            Route route = lifecycleAccess.capture(acquired.workspace(), orgId);
            objectSpool = createObjectSpool();
            auditService.recordStrictIndependentScoped(
                AUDIT_ACTION,
                "workspace",
                acquired.workspace().id(),
                null,
                orgId,
                "workspace:" + acquired.workspace().id(),
                "Tenant export authorized and streaming started",
                Map.of("declaredTableCount", declaredTableCount()));
            TenantExportDownload download = new TenantExportDownload(
                acquired.workspace().id(),
                acquired.workspace().orgId(),
                actorId,
                route,
                operationLease,
                objectSpool);
            return download;
        } catch (RuntimeException | Error exception) {
            cleanupBeforeTransfer(exception, operationLease, objectSpool);
            throw exception;
        }
    }

    private void writeBundle(
            int orgId,
            int workspaceId,
            int actorId,
            Route route,
            Path objectSpool,
            OutputStream output,
            TrackedResource outputResource,
            TenantExportExecution execution) throws IOException {
        lifecycleAccess.withRoute(route, actorId, () -> {
            try {
                writeRoutedBundle(
                    orgId,
                    workspaceId,
                    actorId,
                    objectSpool,
                    output,
                    outputResource,
                    execution);
                return null;
            } catch (IOException exception) {
                throw new ExportWriteException(exception);
            }
        });
    }

    private void writeRoutedBundle(
            int orgId,
            int workspaceId,
            int actorId,
            Path objectSpool,
            OutputStream output,
            TrackedResource outputResource,
            TenantExportExecution execution) throws IOException {
        Instant generatedAt = clock.instant();
        OutputStream ownedOutput = new FilterOutputStream(output) {
            @Override
            public void close() throws IOException {
                outputResource.close();
            }
        };
        try (ZipOutputStream zip = new ZipOutputStream(ownedOutput)) {
            Snapshot snapshot =
                snapshotTransaction.capture(workspaceId, zip, objectSpool, execution);
            long writtenObjects =
                writeObjects(zip, workspaceId, actorId, objectSpool, execution);
            if (writtenObjects != snapshot.objectCount()) {
                throw new IOException("Tenant export object-reference spool is incomplete");
            }
            execution.checkActive();
            Manifest manifest = new Manifest(
                1,
                generatedAt,
                orgId,
                workspaceId,
                "jsonl",
                snapshot.tables().stream()
                    .map(TenantExportService::manifestTable)
                    .toList(),
                snapshot.objectCount());
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(objectMapper.writeValueAsBytes(manifest));
            zip.write('\n');
            zip.closeEntry();
            zip.finish();
        }
    }

    private long writeObjects(
            ZipOutputStream zip,
            int workspaceId,
            int actorId,
            Path objectSpool,
            TenantExportExecution execution) throws IOException {
        long objectCount = 0;
        BufferedReader reader = Files.newBufferedReader(objectSpool, StandardCharsets.UTF_8);
        try (TrackedResource readerResource = execution.track(reader)) {
            String line;
            while ((line = reader.readLine()) != null) {
                execution.checkActive();
                if (line.isEmpty()) {
                    throw new IOException("Tenant export object-reference spool is malformed");
                }
                ActiveObjectReference reference =
                    objectMapper.readValue(line, ActiveObjectReference.class);
                writeObject(zip, workspaceId, actorId, reference, execution);
                objectCount = Math.addExact(objectCount, 1);
            }
        }
        return objectCount;
    }

    private static int declaredTableCount() {
        return Math.addExact(
            TenantLifecycleRegistry.declarations().size(),
            ControlWorkspaceLifecycleRegistry.declarations().size());
    }

    private void writeObject(
            ZipOutputStream zip,
            int workspaceId,
            int actorId,
            ActiveObjectReference reference,
            TenantExportExecution execution) throws IOException {
        long objectDeadlineNanos =
            execution.boundedDeadlineNanos(properties.getExportObjectReadTimeout());
        ManagedTenantObject object = openExportObject(
            workspaceId,
            actorId,
            reference,
            execution,
            objectDeadlineNanos);
        try (TrackedResource objectResource = execution.track(object)) {
            execution.checkActive();
            zip.putNextEntry(new ZipEntry("objects/" + object.objectKey()));
            copyExact(object, zip, execution);
            zip.closeEntry();
        }
    }

    private ManagedTenantObject openExportObject(
            int workspaceId,
            int actorId,
            ActiveObjectReference reference,
            TenantExportExecution execution,
            long objectDeadlineNanos) throws IOException {
        TooManyRequestsException admissionFailure = null;
        while (true) {
            execution.checkActive();
            long remainingNanos = objectDeadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                if (admissionFailure != null) {
                    throw admissionFailure;
                }
                throw new ServiceUnavailableException(
                    "Tenant export object read deadline was reached");
            }
            try {
                return managedObjectService.openTenantExportObject(
                    workspaceId,
                    actorId,
                    reference,
                    Duration.ofNanos(remainingNanos));
            } catch (TooManyRequestsException exception) {
                admissionFailure = exception;
                waitForObjectAdmission(objectDeadlineNanos);
            }
        }
    }

    private static void waitForObjectAdmission(long objectDeadlineNanos) {
        long remainingNanos = objectDeadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return;
        }
        LockSupport.parkNanos(
            Math.min(remainingNanos, OBJECT_ADMISSION_RETRY_NANOS));
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException(
                "Tenant export object admission wait was interrupted");
        }
    }

    private static void copyExact(
            ManagedTenantObject object,
            ZipOutputStream output,
            TenantExportExecution execution) throws IOException {
        long expectedLength = object.expectedLength();
        if (expectedLength < 0) {
            throw new IOException("Managed export object length is invalid");
        }
        byte[] buffer = new byte[8192];
        long copied = 0;
        while (copied < expectedLength) {
            execution.checkActive();
            int requested = Math.toIntExact(
                Math.min(buffer.length, expectedLength - copied));
            int read = object.inputStream().read(buffer, 0, requested);
            execution.checkActive();
            if (read == -1) {
                throw new IOException("Managed export object ended before its declared length");
            }
            output.write(buffer, 0, read);
            copied = Math.addExact(copied, read);
        }
        execution.checkActive();
        int surplus = object.inputStream().read();
        execution.checkActive();
        if (surplus != -1) {
            throw new IOException("Managed export object exceeded its declared length");
        }
    }

    private static ManifestTable manifestTable(CapturedTable table) {
        return new ManifestTable(table.name(), table.path(), table.rowCount());
    }

    private static Path createObjectSpool() {
        try {
            return Files.createTempFile(
                "connex-tenant-export-",
                ".objects",
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
        } catch (IOException | UnsupportedOperationException exception) {
            throw new ServiceUnavailableException(
                "Tenant export object-reference capture is unavailable",
                exception);
        }
    }

    private void cleanupBeforeTransfer(
            Throwable primary,
            OperationLease operationLease,
            Path objectSpool) {
        Throwable cleanupFailure = deleteSpool(objectSpool, null);
        if (cleanupFailure == null && operationLease != null) {
            cleanupFailure = releaseOperationLease(operationLease, cleanupFailure);
        }
        if (cleanupFailure != null) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    private Throwable releaseOperationLease(
            OperationLease operationLease,
            Throwable priorFailure) {
        try {
            tenantWorkScope.unrouted(() -> {
                controlOperations.release(operationLease);
                return null;
            });
            return priorFailure;
        } catch (RuntimeException | Error exception) {
            return appendFailure(priorFailure, exception);
        }
    }

    private static Throwable deleteSpool(Path objectSpool, Throwable priorFailure) {
        if (objectSpool == null) {
            return priorFailure;
        }
        try {
            Files.deleteIfExists(objectSpool);
            return priorFailure;
        } catch (IOException | RuntimeException | Error exception) {
            return appendFailure(priorFailure, exception);
        }
    }

    private static Throwable appendFailure(Throwable priorFailure, Throwable failure) {
        if (priorFailure == null) {
            return failure;
        }
        priorFailure.addSuppressed(failure);
        return priorFailure;
    }

    @PreDestroy
    void shutdownDeadlineExecutor() {
        deadlineExecutor.shutdownNow();
        cancellationExecutor.shutdown();
    }

    private static ScheduledThreadPoolExecutor deadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
            1,
            Thread.ofPlatform().daemon().name("tenant-export-deadline-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static ThreadPoolExecutor cancellationExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            MAX_CONCURRENT_EXPORTS,
            MAX_CONCURRENT_EXPORTS,
            0,
            TimeUnit.NANOSECONDS,
            new ArrayBlockingQueue<>(MAX_CONCURRENT_EXPORTS),
            Thread.ofPlatform().daemon().name("tenant-export-cancellation-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
        return executor;
    }

    /** Single-use streamed export descriptor that owns all export resources. */
    public final class TenantExportDownload {
        private final int workspaceId;
        private final int orgId;
        private final int actorId;
        private final Route route;
        private final OperationLease operationLease;
        private final AtomicReference<Path> objectSpool;
        private final TenantExportExecution execution;

        private TenantExportDownload(
                int workspaceId,
                int orgId,
                int actorId,
                Route route,
                OperationLease operationLease,
                Path objectSpool) {
            this.workspaceId = workspaceId;
            this.orgId = orgId;
            this.actorId = actorId;
            this.route = route;
            this.operationLease = operationLease;
            this.objectSpool = new AtomicReference<>(objectSpool);
            execution = new TenantExportExecution(
                properties.getExportTimeout(),
                cancellationExecutor,
                this::cleanupOwnedResources);
            execution.armDeadline(deadlineExecutor);
        }

        /** Trusted response filename. */
        public String filename() {
            return "connex-workspace-" + workspaceId + "-export.zip";
        }

        /** Writes the ZIP once and releases every export resource on every exit. */
        public void writeTo(OutputStream output) throws IOException {
            execution.begin();
            Throwable primary = null;
            try {
                TrackedResource outputResource = execution.track(output);
                Path spool = objectSpool.get();
                if (spool == null) {
                    throw new IllegalStateException("Tenant export object-reference spool is unavailable");
                }
                writeBundle(
                    orgId,
                    workspaceId,
                    actorId,
                    route,
                    spool,
                    output,
                    outputResource,
                    execution);
            } catch (ExportWriteException exception) {
                primary = exception.ioException();
                throw exception.ioException();
            } catch (IOException | RuntimeException | Error exception) {
                primary = exception;
                throw exception;
            } finally {
                execution.writerFinished(primary);
            }
        }

        /** Idempotently signals cancellation without blocking on resource cleanup. */
        public void cancel() {
            execution.cancel();
        }

        /** Remaining servlet timeout derived from the same monotonic export deadline. */
        public long remainingTimeoutMillis() {
            return execution.remainingTimeoutMillis();
        }

        private Throwable cleanupOwnedResources(Throwable priorFailure) {
            Throwable cleanupFailure = deleteOwnedSpool(priorFailure);
            if (cleanupFailure == null) {
                cleanupFailure = releaseOperationLease(operationLease, null);
            }
            return cleanupFailure;
        }

        private Throwable deleteOwnedSpool(Throwable priorFailure) {
            Path spool = objectSpool.get();
            if (spool == null) {
                return priorFailure;
            }
            try {
                Files.deleteIfExists(spool);
                objectSpool.compareAndSet(spool, null);
                return priorFailure;
            } catch (IOException | RuntimeException | Error exception) {
                return appendFailure(priorFailure, exception);
            }
        }
    }

    private record Manifest(
            int schemaVersion,
            Instant generatedAt,
            int organizationId,
            int workspaceId,
            String format,
            List<ManifestTable> tables,
            long objectCount) {
    }

    private record ManifestTable(String name, String path, long rowCount) {
    }

    private static final class ExportWriteException extends RuntimeException {
        private final IOException ioException;

        private ExportWriteException(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }

        private IOException ioException() {
            return ioException;
        }
    }
}
