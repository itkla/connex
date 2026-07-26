package ooo.klae.connex.backend.services;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ActiveObjectReference;
import ooo.klae.connex.backend.services.TenantLifecycleAccess.Route;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.AcquiredWorkspace;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.OperationLease;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedTenantObject;
import ooo.klae.connex.backend.tenant.TenantLifecycleProperties;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;
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
 */
@Service
@RequiredArgsConstructor
public class TenantExportService {
    private static final String AUDIT_ACTION = "org.workspace.export";

    private final OrgMemberService orgMemberService;
    private final SessionSecurityService sessionSecurityService;
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
        orgMemberService.requireOrgAdmin(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        AcquiredWorkspace acquired = controlOperations.acquireExport(orgId, workspaceId);
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
        long objectCount = 0;
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
                    objectCount++;
                    writeObject(zip, workspaceId, actorId, reference);
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
                objectCount);
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(objectMapper.writeValueAsBytes(manifest));
            zip.write('\n');
            zip.closeEntry();
            zip.finish();
        }
    }

    private void writeObject(
            ZipOutputStream zip,
            int workspaceId,
            int actorId,
            ActiveObjectReference reference) throws IOException {
        try (ManagedTenantObject object = managedObjectService.openTenantExportObject(
                workspaceId,
                actorId,
                reference,
                properties.getExportObjectReadTimeout())) {
            zip.putNextEntry(new ZipEntry("objects/" + object.objectKey()));
            long copied = object.inputStream().transferTo(zip);
            zip.closeEntry();
            if (copied != object.expectedLength()) {
                throw new IOException("Managed export object length changed while streaming");
            }
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
