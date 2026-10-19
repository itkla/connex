package ooo.klae.connex.backend.services;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
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
import ooo.klae.connex.backend.ai.mission.AiAgentContinuationCodec;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.dto.ActiveObjectReference;
import ooo.klae.connex.backend.dto.ClientErrorSupportRowDto;
import ooo.klae.connex.backend.mappers.TenantLifecycleMapper;
import ooo.klae.connex.backend.services.TenantExportExecution.TrackedResource;
import ooo.klae.connex.backend.services.TenantExportQueryCancellationInterceptor.Scope;
import ooo.klae.connex.backend.tenant.ControlWorkspaceLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;
import ooo.klae.connex.backend.tenant.TenantLifecycleProperties;
import tools.jackson.databind.ObjectMapper;

/** Captures one point-in-time tenant export through streaming cursors. */
@Service
@RequiredArgsConstructor
public class TenantExportSnapshotTransaction {
    private final TenantLifecycleMapper mapper;
    private final ObjectMapper objectMapper;
    private final ClientErrorService clientErrorService;
    private final TenantLifecycleProperties properties;

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
            for (ControlWorkspaceLifecycleRegistry.TableLifecycle declaration
                    : ControlWorkspaceLifecycleRegistry.declarations().values()) {
                execution.checkActive();
                CapturedTable table = writeControlWorkspaceTable(
                    workspaceId, declaration, zip, execution);
                if (table != null) {
                    tables.add(table);
                }
            }
            long objectCount = writeObjectReferences(workspaceId, objectSpool, execution);
            return new Snapshot(tables, objectCount);
        }
    }

    private CapturedTable writeControlWorkspaceTable(
            int workspaceId,
            ControlWorkspaceLifecycleRegistry.TableLifecycle declaration,
            ZipOutputStream zip,
            TenantExportExecution execution) throws IOException {
        ControlWorkspaceLifecycleRegistry.TableLifecycle registered =
            ControlWorkspaceLifecycleRegistry.requireRegistered(declaration);
        return switch (registered.exportKind()) {
            case CLIENT_ERROR -> writeClientErrors(workspaceId, registered, zip, execution);
        };
    }

    private CapturedTable writeClientErrors(
            int workspaceId,
            ControlWorkspaceLifecycleRegistry.TableLifecycle declaration,
            ZipOutputStream zip,
            TenantExportExecution execution) throws IOException {
        long afterId = 0;
        long rows = 0;
        boolean entryOpen = false;
        try {
            while (true) {
                List<ClientErrorSupportRowDto> page = clientErrorService.workspaceExportPage(
                    workspaceId, afterId, properties.getTableBatchSize());
                if (page.isEmpty()) {
                    break;
                }
                if (!entryOpen) {
                    zip.putNextEntry(new ZipEntry("data/" + declaration.table() + ".jsonl"));
                    entryOpen = true;
                }
                for (ClientErrorSupportRowDto row : page) {
                    execution.checkActive();
                    if (row.id() == null || row.id() <= afterId) {
                        throw new IllegalStateException(
                            "Control-workspace export keyset did not advance");
                    }
                    zip.write(objectMapper.writeValueAsBytes(row));
                    zip.write('\n');
                    afterId = row.id();
                    rows = Math.addExact(rows, 1);
                }
                if (page.size() < properties.getTableBatchSize()) {
                    break;
                }
            }
        } finally {
            if (entryOpen) {
                zip.closeEntry();
            }
        }
        return rows == 0
            ? null
            : new CapturedTable(
                declaration.table(),
                "data/" + declaration.table() + ".jsonl",
                rows);
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
                restoreUtf8Payloads(row);
                if ("ai_agent_run".equals(registered.table())) {
                    validateMission(row);
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

    private void restoreUtf8Payloads(Map<String, Object> row) throws IOException {
        for (String column : List.of("plan_json", "checkpoint_json", "memory_json", "arguments_json", "result_json")) {
            String transportColumn = "utf8_" + column;
            if (row.containsKey(transportColumn)) {
                Object bytes = row.remove(transportColumn);
                if (bytes != null && !(bytes instanceof byte[])) {
                    throw new IOException("Invalid mission export transport");
                }
                try {
                    row.put(column, ooo.klae.connex.backend.mappers.AiIdentifierUtf8TextTypeHandler.decode((byte[]) bytes));
                } catch (java.sql.SQLException exception) {
                    throw new IOException("Invalid mission export encoding", exception);
                }
            }
        }
    }

    private void validateMission(Map<String, Object> row) {
        if (row.get("purged_at") != null) {
            for (String column : List.of("session_id", "goal", "scope_json", "request_hash", "scope_fingerprint",
                    "report_message_id", "active_segment_id", "plan_schema_version", "plan_json",
                    "checkpoint_schema_version", "checkpoint_json", "memory_schema_version", "memory_json")) {
                if (row.get(column) != null) {
                    throw new IllegalStateException("Mission tombstone contains residual content");
                }
            }
            for (String column : List.of("plan_revision", "checkpoint_revision", "last_committed_segment_no")) {
                if (missionNumber(row, column) != 0) {
                    throw new IllegalStateException("Mission tombstone contains residual metadata");
                }
            }
            return;
        }
        AiAgentRun run = new AiAgentRun();
        run.setId(Math.toIntExact(missionNumber(row, "id")));
        run.setWorkspaceId(Math.toIntExact(missionNumber(row, "workspace_id")));
        run.setMissionType(missionText(row, "mission_type"));
        run.setDefinitionVersion(Math.toIntExact(missionNumber(row, "definition_version")));
        run.setRuntimeContractVersion(Math.toIntExact(missionNumber(row, "runtime_contract_version")));
        run.setPrivacyGeneration(missionNumber(row, "privacy_generation"));
        if (row.get("scope_fingerprint") instanceof byte[] fingerprint) {
            run.setScopeFingerprint(fingerprint);
        }
        run.setScopeJson(missionText(row, "scope_json"));
        run.setPlanSchemaVersion(missionSchema(row, "plan_schema_version"));
        run.setPlanRevision(missionNumber(row, "plan_revision"));
        run.setPlanJson(missionText(row, "plan_json"));
        run.setCheckpointSchemaVersion(missionSchema(row, "checkpoint_schema_version"));
        run.setCheckpointRevision(missionNumber(row, "checkpoint_revision"));
        run.setCheckpointJson(missionText(row, "checkpoint_json"));
        run.setMemorySchemaVersion(missionSchema(row, "memory_schema_version"));
        run.setMemoryJson(missionText(row, "memory_json"));
        run.setLastCommittedSegmentNo(Math.toIntExact(missionNumber(row, "last_committed_segment_no")));
        var codec = new AiAgentContinuationCodec();
        var snapshot = codec.decode(run);
        if (snapshot != null) {
            codec.validateExecutable(run, snapshot);
        }
    }

    private Integer missionSchema(Map<String, Object> row, String column) {
        return row.get(column) == null ? null : Math.toIntExact(missionNumber(row, column));
    }

    private long missionNumber(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Mission export metadata is missing");
        }
        return new BigDecimal(number.toString()).longValueExact();
    }

    private String missionText(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalStateException("Mission export payload has an invalid type");
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
