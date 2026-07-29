package ooo.klae.connex.backend.services;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ActiveObjectReference;
import ooo.klae.connex.backend.mappers.TenantLifecycleMapper;
import ooo.klae.connex.backend.services.TenantExportExecution.TrackedResource;
import ooo.klae.connex.backend.services.TenantExportQueryCancellationInterceptor.Scope;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;
import tools.jackson.databind.ObjectMapper;

/** Captures one point-in-time tenant export through streaming cursors. */
@Service
@RequiredArgsConstructor
public class TenantExportSnapshotTransaction {
    private final TenantLifecycleMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * Writes table rows to the ZIP and object references to the private spool in one snapshot.
     */
    @Transactional(
        readOnly = true,
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.REPEATABLE_READ)
    public Snapshot capture(
            int workspaceId,
            ZipOutputStream zip,
            Path objectSpool,
            TenantExportExecution execution) throws IOException {
        try (Scope ignored = TenantExportQueryCancellationInterceptor.openScope(execution)) {
            List<CapturedTable> tables = new ArrayList<>();
            for (TableLifecycle declaration : TenantLifecycleRegistry.declarations().values()) {
                execution.checkActive();
                CapturedTable table = writeTable(workspaceId, declaration, zip, execution);
                if (table != null) {
                    tables.add(table);
                }
            }
            long objectCount = writeObjectReferences(workspaceId, objectSpool, execution);
            return new Snapshot(tables, objectCount);
        }
    }

    private CapturedTable writeTable(
            int workspaceId,
            TableLifecycle declaration,
            ZipOutputStream zip,
            TenantExportExecution execution) throws IOException {
        TableLifecycle registered = TenantLifecycleRegistry.requireRegistered(declaration);
        long rows = 0;
        boolean entryOpen = false;
        try (Cursor<Map<String, Object>> cursor =
                mapper.streamRows(workspaceId, registered)) {
            for (Map<String, Object> row : cursor) {
                execution.checkActive();
                if (!entryOpen) {
                    zip.putNextEntry(new ZipEntry("data/" + registered.table() + ".jsonl"));
                    entryOpen = true;
                }
                zip.write(objectMapper.writeValueAsBytes(row));
                zip.write('\n');
                rows = Math.addExact(rows, 1);
            }
        } finally {
            if (entryOpen) {
                zip.closeEntry();
            }
        }
        return rows == 0
            ? null
            : new CapturedTable(
                registered.table(),
                "data/" + registered.table() + ".jsonl",
                rows);
    }

    private long writeObjectReferences(
            int workspaceId,
            Path objectSpool,
            TenantExportExecution execution) throws IOException {
        long objectCount = 0;
        OutputStream output = Files.newOutputStream(
                objectSpool,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try (TrackedResource outputResource = execution.track(output)) {
            try (Cursor<ActiveObjectReference> cursor =
                    mapper.streamActiveObjectReferences(workspaceId)) {
                for (ActiveObjectReference reference : cursor) {
                    execution.checkActive();
                    output.write(objectMapper.writeValueAsBytes(reference));
                    output.write('\n');
                    objectCount = Math.addExact(objectCount, 1);
                }
            }
        }
        return objectCount;
    }

    /** Exact table and object counts captured from one repeatable-read snapshot. */
    public record Snapshot(List<CapturedTable> tables, long objectCount) {
        public Snapshot {
            tables = List.copyOf(tables);
        }
    }

    /** One non-empty JSONL table entry written into the export ZIP. */
    public record CapturedTable(String name, String path, long rowCount) {
    }
}
