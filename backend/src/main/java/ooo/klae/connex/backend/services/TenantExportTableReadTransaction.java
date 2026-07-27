package ooo.klae.connex.backend.services;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ActiveObjectReference;
import ooo.klae.connex.backend.mappers.TenantLifecycleMapper;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;
import tools.jackson.databind.ObjectMapper;

/** Per-table and per-page routed read transactions used by streamed exports. */
@Service
@RequiredArgsConstructor
public class TenantExportTableReadTransaction {
    private final TenantLifecycleMapper mapper;
    private final ObjectMapper objectMapper;

    /** Counts one registry-verified table in a short routed transaction. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public long count(int workspaceId, TableLifecycle declaration) {
        return mapper.countRows(
            workspaceId,
            TenantLifecycleRegistry.requireRegistered(declaration));
    }

    /**
     * Streams one table cursor into a JSON Lines ZIP entry and returns the
     * exact written row count.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public long writeTable(
            int workspaceId,
            TableLifecycle declaration,
            ZipOutputStream zip) throws IOException {
        TableLifecycle registered = TenantLifecycleRegistry.requireRegistered(declaration);
        long rows = 0;
        boolean entryOpen = false;
        try (Cursor<Map<String, Object>> cursor = mapper.streamRows(workspaceId, registered)) {
            for (Map<String, Object> row : cursor) {
                if (!entryOpen) {
                    zip.putNextEntry(new ZipEntry("data/" + registered.table() + ".jsonl"));
                    entryOpen = true;
                }
                zip.write(objectMapper.writeValueAsBytes(row));
                zip.write('\n');
                rows++;
            }
        } finally {
            if (entryOpen) {
                zip.closeEntry();
            }
        }
        return rows;
    }

    /** Reads one bounded page of active managed-object metadata. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<ActiveObjectReference> activeObjects(
            int workspaceId,
            String afterKey,
            int limit) {
        return List.copyOf(
            mapper.findActiveObjectReferencesAfter(workspaceId, afterKey, limit));
    }

    /**
     * Re-reads one exact active managed-object reference in a fresh transaction so a
     * streaming export can tell a committed metadata delete from missing bytes.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ActiveObjectReference activeObject(int workspaceId, String objectKey) {
        return mapper.findActiveObjectReference(workspaceId, objectKey);
    }
}
