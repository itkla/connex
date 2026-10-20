package ooo.klae.connex.backend.services;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import ooo.klae.connex.backend.ai.mission.AiAgentMissionCatalog;
import ooo.klae.connex.backend.ai.mission.AiAgentSourceObservationsCodec;
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
                } else if ("ai_agent_action".equals(registered.table())) {
                    validateMissionAction(row);
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
        for (String column : List.of("plan_json", "checkpoint_json", "memory_json", "arguments_json", "result_json", "source_versions_json")) {
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
                    "checkpoint_schema_version", "checkpoint_json", "memory_schema_version", "memory_json",
                    "proposal_frozen_at", "proposal_first_prepared_at", "review_expires_at")) {
                if (row.get(column) != null) {
                    throw new IllegalStateException("Mission tombstone contains residual content");
                }
            }
            for (String column : List.of("plan_revision", "checkpoint_revision", "last_committed_segment_no",
                    "proposal_membership_revision")) {
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
        validateMissionReview(row, run.getRuntimeContractVersion());
        var codec = new AiAgentContinuationCodec();
        var snapshot = codec.decode(run);
        if (snapshot != null) {
            codec.validateExecutable(run, snapshot);
        }
    }

    private void validateMissionReview(Map<String, Object> row, int runtime) {
        long revision = missionNumber(row, "proposal_membership_revision");
        Instant frozen = missionInstant(row, "proposal_frozen_at");
        Instant first = missionInstant(row, "proposal_first_prepared_at");
        Instant expiry = missionInstant(row, "review_expires_at");
        if (revision < 0 || (runtime < 3 && (revision != 0 || frozen != null || first != null || expiry != null))
                || (frozen == null && expiry != null)
                || (frozen != null && (revision == 0 || ((first == null) != (expiry == null))))) {
            throw new IllegalStateException("Invalid mission review export metadata");
        }
        if (expiry != null) {
            Instant hard = missionInstant(row, "hard_deadline_at");
            Instant allowance = java.util.Objects.requireNonNull(first).plusSeconds(72 * 3600);
            Instant expected = hard != null && hard.isBefore(allowance) ? hard : allowance;
            if (!expected.equals(expiry)) {
                throw new IllegalStateException("Invalid mission review export deadline");
            }
        }
    }

    private void validateMissionAction(Map<String, Object> row) {
        validateMissionActionRoot(row);
        long root = missionNumber(row, "root_run_id");
        if (root <= 0 || missionNumber(row, "producer_run_id") != root
                || missionNumber(row, "privacy_generation") <= 0
                || !canonicalUuid(missionText(row, "action_slot"))
                || missionNumber(row, "plan_revision") <= 0
                || !java.util.Set.of("person", "company", "deal").contains(missionText(row, "target_kind"))
                || missionNumber(row, "target_id") <= 0
                || !java.util.Objects.equals(missionText(row, "semantic_slot_key"),
                    missionText(row, "target_kind") + ":" + missionNumber(row, "target_id") + ":" + missionText(row, "tool_name"))
                || missionText(row, "plan_item_key") == null
                || !missionText(row, "plan_item_key").matches("p[0-9]{4}")) {
            throw new IllegalStateException("Invalid mission action export identity");
        }
        String preparation = missionText(row, "preparation_id");
        if (preparation == null) {
            for (String column : List.of("intent_fingerprint", "prepared_at", "source_observed_at", "source_versions_json",
                    "origin_segment_id", "origin_execution_generation", "output_ordinal")) {
                if (row.get(column) != null) {
                    throw new IllegalStateException("Unbound mission action contains preparation");
                }
            }
            if (missionNumber(row, "mission_export_canonical_count") != 0) {
                throw new IllegalStateException("Unbound mission action contains a canonical proposal");
            }
            removeMissionExportProjection(row);
            return;
        }
        Instant prepared = missionInstant(row, "prepared_at");
        if (!canonicalUuid(preparation) || !(row.get("intent_fingerprint") instanceof byte[] fingerprint)
                || fingerprint.length != 32 || prepared == null
                || missionNumber(row, "origin_segment_id") <= 0
                || missionNumber(row, "origin_execution_generation") <= 0
                || missionNumber(row, "origin_execution_generation") != missionNumber(row, "mission_export_origin_generation")
                || missionNumber(row, "mission_export_origin_privacy") != missionNumber(row, "privacy_generation")
                || missionNumber(row, "mission_export_canonical_count") != 1
                || (row.get("output_ordinal") != null && (missionNumber(row, "output_ordinal") < 1
                    || missionNumber(row, "output_ordinal") > 50))) {
            throw new IllegalStateException("Invalid mission action export binding");
        }
        var observations = new AiAgentSourceObservationsCodec().decode(missionText(row, "source_versions_json"));
        observations.requireComplete(missionText(row, "target_kind"), Math.toIntExact(missionNumber(row, "target_id")), prepared);
        if (!observations.firstObservedAt().equals(missionInstant(row, "source_observed_at"))) {
            throw new IllegalStateException("Invalid mission action export anchor");
        }
        removeMissionExportProjection(row);
    }

    private void validateMissionActionRoot(Map<String, Object> row) {
        if (!AiAgentMissionCatalog.supports(missionText(row, "mission_export_type"),
                Math.toIntExact(missionNumber(row, "mission_export_definition")),
                Math.toIntExact(missionNumber(row, "mission_export_runtime")))
                || missionNumber(row, "mission_export_runtime") != 3
                || missionNumber(row, "mission_export_privacy") != missionNumber(row, "privacy_generation")
                || missionNumber(row, "plan_revision") > missionNumber(row, "mission_export_plan_revision")) {
            throw new IllegalStateException("Invalid mission action export runtime");
        }
        var scope = objectMapper.readTree(missionExportText(row, "mission_export_scope"));
        String kind = missionText(row, "target_kind");
        if (!"record".equals(scope.path("kind").asString()) || !java.util.Objects.equals(kind, scope.path("recordKind").asString())
                || scope.path("recordId").asInt() != missionNumber(row, "target_id")
                || !AiAgentMissionCatalog.confirmTools(kind).contains(missionText(row, "tool_name"))) {
            throw new IllegalStateException("Invalid mission action export target");
        }
        var plan = objectMapper.readTree(missionExportText(row, "mission_export_plan"));
        boolean retained = false;
        for (var item : plan.path("items")) {
            retained |= java.util.Objects.equals(missionText(row, "plan_item_key"), item.path("key").asString());
        }
        if (!retained || missionNumber(row, "mission_export_bound_count") > 50) {
            throw new IllegalStateException("Invalid mission action export plan association");
        }
        var checkpoint = objectMapper.readTree(missionExportText(row, "mission_export_checkpoint"));
        var references = checkpoint.path("actionSlotReferences");
        boolean associated = false;
        for (var reference : references) {
            associated |= java.util.Objects.equals(missionText(row, "action_slot"), reference.path("slotKey").asString())
                    && java.util.Objects.equals(missionText(row, "plan_item_key"), reference.path("planItemKey").asString());
        }
        if (!references.isArray() || !associated || references.size() != missionNumber(row, "mission_export_slot_count")
                || references.size() > 50) {
            throw new IllegalStateException("Invalid mission action export inventory");
        }
        boolean frozen = row.get("mission_export_frozen") != null;
        long outputCount = missionNumber(row, "mission_export_output_count");
        if ((!frozen && outputCount != 0) || (frozen && (outputCount != missionNumber(row, "mission_export_bound_count")
                || outputCount != missionNumber(row, "mission_export_max_ordinal")
                || (row.get("preparation_id") != null && row.get("output_ordinal") == null)))) {
            throw new IllegalStateException("Invalid mission action export membership");
        }
    }

    private String missionExportText(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (!(value instanceof byte[] bytes)) {
            throw new IllegalStateException("Invalid mission action export transport");
        }
        try {
            return ooo.klae.connex.backend.mappers.AiIdentifierUtf8TextTypeHandler.decode(bytes);
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Invalid mission action export encoding", exception);
        }
    }

    private void removeMissionExportProjection(Map<String, Object> row) {
        row.keySet().removeIf(column -> column.startsWith("mission_export_"));
    }

    private boolean canonicalUuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private Instant missionInstant(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        if (value instanceof LocalDateTime time) {
            return time.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        throw new IllegalStateException("Mission export timestamp has an invalid type");
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
