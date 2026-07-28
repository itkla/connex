package ooo.klae.connex.backend.services;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ActiveObjectReference;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.TenantLifecycleAccess.Route;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.AcquiredWorkspace;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.OperationLease;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedTenantObject;
import ooo.klae.connex.backend.tenant.TenantLifecycleProperties;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/**
 * Authorizes and streams a complete workspace export without materializing a
 * table or ZIP in memory. Each table uses its own read transaction, so the
 * bundle is deliberately not a cross-table point-in-time snapshot; the
 * manifest records the exact rows written. This avoids retaining one database
 * snapshot and connection for the duration of large object downloads.
 *
 * <p>APPI-restricted people are included, together with
 * {@code suspended_at} and {@code provision_ceased_at}, because an
 * offboarding export represents the organization's complete lawful holdings.
 * A failure after response streaming starts necessarily produces a truncated
 * ZIP; strict authorization audit is durable before any response body begins.
 *
 * <p>An object whose bytes are missing is skipped only when its metadata row is
 * provably gone — re-read in a fresh routed transaction — so a rotated bucket,
 * a wrong key prefix, or a restore against empty storage still fails hard
 * instead of returning an attachment-free bundle. Every skip carries a strict
 * audit entry naming the exact object key, the manifest reports the enumerated
 * total alongside the written and skipped counts that must sum to it, and
 * exceeding the skip ceiling aborts the bundle.
 */
@Service
@RequiredArgsConstructor
public class TenantExportService {
    private static final String AUDIT_ACTION = "org.workspace.export";
    private static final String SKIP_AUDIT_ACTION = "org.workspace.export.object_skipped";
    private static final long ADMISSION_RETRY_NANOS = Duration.ofMillis(100).toNanos();

    private final OrgMemberService orgMemberService;
    private final SessionSecurityService sessionSecurityService;
    private final TenantWorkScope tenantWorkScope;
    private final TenantLifecycleControlOperations controlOperations;
    private final TenantLifecycleAccess lifecycleAccess;
    private final TenantExportTableReadTransaction readTransaction;
    private final ManagedObjectService managedObjectService;
    private final AuditService auditService;
    private final TenantLifecycleProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Performs synchronous authorization, placement capture, preflight counts,
     * lease acquisition, and strict audit before returning a streaming writer.
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
        boolean transferred = false;
        try {
            Route route = lifecycleAccess.capture(acquired.workspace(), orgId);
            Preflight preflight = lifecycleAccess.withRoute(
                route,
                actorId,
                () -> preflight(route.workspaceId()));
            auditService.recordStrictIndependentScoped(
                AUDIT_ACTION,
                "workspace",
                workspaceId,
                null,
                orgId,
                "workspace:" + workspaceId,
                "Tenant export authorized and streaming started",
                Map.of(
                    "declaredTableCount", TenantLifecycleRegistry.declarations().size(),
                    "rowCount", preflight.rowCount(),
                    "objectCount", preflight.objectCount()));
            TenantExportDownload download = new TenantExportDownload(
                acquired.workspace().id(),
                acquired.workspace().orgId(),
                actorId,
                route,
                acquired.lease());
            transferred = true;
            return download;
        } finally {
            if (!transferred) {
                controlOperations.release(acquired.lease());
            }
        }
    }

    private Preflight preflight(int workspaceId) {
        long rows = 0;
        for (TableLifecycle declaration : TenantLifecycleRegistry.declarations().values()) {
            rows = Math.addExact(rows, readTransaction.count(workspaceId, declaration));
        }
        long objects = 0;
        String afterKey = "";
        while (true) {
            List<ActiveObjectReference> page = readTransaction.activeObjects(
                workspaceId,
                afterKey,
                properties.getObjectPageSize());
            if (page.isEmpty()) {
                break;
            }
            objects = Math.addExact(objects, page.size());
            afterKey = page.getLast().objectKey();
        }
        return new Preflight(rows, objects);
    }

    private void writeBundle(
            int orgId,
            int workspaceId,
            int actorId,
            Route route,
            OutputStream output) throws IOException {
        lifecycleAccess.withRoute(route, actorId, () -> {
            try {
                writeRoutedBundle(orgId, workspaceId, actorId, output);
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
            OutputStream output) throws IOException {
        Instant generatedAt = clock.instant();
        List<ManifestTable> tables = new ArrayList<>();
        long enumeratedObjectCount = 0;
        long objectCount = 0;
        long skippedObjectCount = 0;
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (TableLifecycle declaration : TenantLifecycleRegistry.declarations().values()) {
                long rows = readTransaction.writeTable(workspaceId, declaration, zip);
                if (rows > 0) {
                    tables.add(new ManifestTable(
                        declaration.table(),
                        "data/" + declaration.table() + ".jsonl",
                        rows));
                }
            }
            String afterKey = "";
            while (true) {
                List<ActiveObjectReference> page = readTransaction.activeObjects(
                    workspaceId,
                    afterKey,
                    properties.getObjectPageSize());
                if (page.isEmpty()) {
                    break;
                }
                for (ActiveObjectReference reference : page) {
                    enumeratedObjectCount++;
                    if (writeObject(zip, workspaceId, actorId, reference)) {
                        objectCount++;
                    } else {
                        skippedObjectCount++;
                        recordSkippedObject(orgId, workspaceId, reference, skippedObjectCount);
                    }
                }
                afterKey = page.getLast().objectKey();
            }
            Manifest manifest = new Manifest(
                1,
                generatedAt,
                orgId,
                workspaceId,
                "jsonl",
                List.copyOf(tables),
                enumeratedObjectCount,
                objectCount,
                skippedObjectCount);
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(objectMapper.writeValueAsBytes(manifest));
            zip.write('\n');
            zip.closeEntry();
            zip.finish();
        }
    }

    private boolean writeObject(
            ZipOutputStream zip,
            int workspaceId,
            int actorId,
            ActiveObjectReference reference) throws IOException {
        ManagedTenantObject object;
        try {
            object = openExportObject(workspaceId, actorId, reference);
        } catch (ResourceNotFoundException exception) {
            if (readTransaction.activeObject(workspaceId, reference.objectKey()) != null) {
                throw exception;
            }
            return false;
        }
        try (object) {
            zip.putNextEntry(new ZipEntry("objects/" + object.objectKey()));
            long copied = object.inputStream().transferTo(zip);
            zip.closeEntry();
            if (copied != object.expectedLength()) {
                throw new IOException("Managed export object length changed while streaming");
            }
        }
        return true;
    }

    private ManagedTenantObject openExportObject(
            int workspaceId,
            int actorId,
            ActiveObjectReference reference) {
        Duration timeout = properties.getExportObjectReadTimeout();
        Instant deadline = clock.instant().plus(timeout);
        while (true) {
            try {
                return managedObjectService.openTenantExportObject(
                    workspaceId,
                    actorId,
                    reference,
                    timeout);
            } catch (TooManyRequestsException exception) {
                if (!clock.instant().isBefore(deadline)) {
                    throw exception;
                }
                parkUntil(deadline);
            }
        }
    }

    private void parkUntil(Instant deadline) {
        long remaining = Duration.between(clock.instant(), deadline).toNanos();
        LockSupport.parkNanos(Math.min(Math.max(remaining, 1), ADMISSION_RETRY_NANOS));
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Tenant export admission wait was interrupted");
        }
    }

    private void recordSkippedObject(
            int orgId,
            int workspaceId,
            ActiveObjectReference reference,
            long skippedObjectCount) {
        tenantWorkScope.unrouted(() -> {
            auditService.recordStrictIndependentScoped(
                SKIP_AUDIT_ACTION,
                "workspace",
                workspaceId,
                null,
                orgId,
                "workspace:" + workspaceId,
                "Tenant export skipped an object deleted while streaming",
                Map.of(
                    "objectKey", reference.objectKey(),
                    "objectKind", reference.kind(),
                    "objectOwnerId", reference.ownerId(),
                    "skippedObjectCount", skippedObjectCount));
            return null;
        });
        if (skippedObjectCount > properties.getMaxSkippedExportObjects()) {
            throw new IllegalStateException(
                "Tenant export skipped more objects than the configured ceiling allows for workspace "
                    + workspaceId);
        }
    }

    private void release(OperationLease lease) {
        controlOperations.release(lease);
    }

    /** Single-use streamed export descriptor that owns its export lease. */
    public final class TenantExportDownload {
        private final int workspaceId;
        private final int orgId;
        private final int actorId;
        private final Route route;
        private final OperationLease lease;
        private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

        private TenantExportDownload(
                int workspaceId,
                int orgId,
                int actorId,
                Route route,
                OperationLease lease) {
            this.workspaceId = workspaceId;
            this.orgId = orgId;
            this.actorId = actorId;
            this.route = route;
            this.lease = lease;
        }

        /** Trusted response filename. */
        public String filename() {
            return "connex-workspace-" + workspaceId + "-export.zip";
        }

        /** Writes the ZIP once and releases the export lease on every exit. */
        public void writeTo(OutputStream output) throws IOException {
            if (!state.compareAndSet(State.NEW, State.WRITING)) {
                throw new IllegalStateException("Tenant export download is single-use");
            }
            try {
                writeBundle(orgId, workspaceId, actorId, route, output);
            } catch (ExportWriteException exception) {
                throw exception.ioException();
            } finally {
                state.set(State.DONE);
                release(lease);
            }
        }

        /** Releases a prepared lease when asynchronous streaming never starts. */
        public void closeIfNotStarted() {
            if (state.compareAndSet(State.NEW, State.DONE)) {
                release(lease);
            }
        }
    }

    private enum State {
        NEW,
        WRITING,
        DONE
    }

    private record Preflight(long rowCount, long objectCount) {
    }

    private record Manifest(
            int schemaVersion,
            Instant generatedAt,
            int organizationId,
            int workspaceId,
            String format,
            List<ManifestTable> tables,
            long enumeratedObjectCount,
            long objectCount,
            long skippedObjectCount) {
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
