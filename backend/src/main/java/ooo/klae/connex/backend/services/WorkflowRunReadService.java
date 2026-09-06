package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowRunView;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowRunDetailDto;
import ooo.klae.connex.backend.dto.WorkflowRunPageDto;
import ooo.klae.connex.backend.dto.WorkflowRunSummaryDto;
import ooo.klae.connex.backend.dto.WorkflowStepRunDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Merges canonical and retained legacy run history behind stable prefixed run keys. */
@Service
@RequiredArgsConstructor
public class WorkflowRunReadService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final int MAX_CURSOR_LENGTH = 512;

    private final WorkflowMapper workflowMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final RuleMapper ruleMapper;
    private final WorkflowDraftCanonicalizer canonicalizer;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowRunPageDto listRuns(int workflowId, Integer requestedLimit, String cursorValue) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Workflow workflow = requireWorkflow(workspaceId, workflowId);
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BadRequestException("Run page limit must be between 1 and 50");
        }
        RunCursor cursor = cursorValue == null || cursorValue.isBlank()
            ? firstCursor(workspaceId, workflowId)
            : decodeCursor(cursorValue);
        List<MergedRun> canonical = canonicalRuns(
            workspaceId, workflowId, cursor, limit + 1);
        List<MergedRun> legacy = legacyRuns(
            workspaceId, workflow.getLegacyRuleId(), cursor, limit + 1);
        List<MergedRun> merged = new ArrayList<>(canonical.size() + legacy.size());
        merged.addAll(canonical);
        merged.addAll(legacy);
        merged.sort(Comparator.comparing(MergedRun::occurredAt)
            .thenComparing(MergedRun::sourceOrder)
            .thenComparing(MergedRun::id)
            .reversed());
        List<MergedRun> page = merged.stream().limit(limit).toList();
        List<WorkflowRunSummaryDto> items = page.stream().map(MergedRun::dto).toList();
        String nextCursor = merged.size() > limit
            ? encodeCursor(advancedCursor(cursor, page))
            : null;
        return new WorkflowRunPageDto(items, nextCursor);
    }

    @Transactional(readOnly = true)
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowRunDetailDto getRun(int workflowId, String runKey) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Workflow workflow = requireWorkflow(workspaceId, workflowId);
        WorkflowRunKey parsed = WorkflowRunKey.parse(runKey);
        if ("canonical".equals(parsed.source())) {
            return canonicalDetail(workspaceId, workflowId, parsed.id());
        }
        if (parsed.id() > Integer.MAX_VALUE || workflow.getLegacyRuleId() == null) {
            throw runNotFound();
        }
        RuleExecution execution = ruleMapper.getExecutionById(
            workspaceId, workflow.getLegacyRuleId(), (int) parsed.id());
        if (execution == null) {
            throw runNotFound();
        }
        return legacyDetail(workflowId, execution);
    }

    private RunCursor firstCursor(int workspaceId, int workflowId) {
        LocalDateTime asOf = workflowRunMapper.currentTimestamp(workspaceId, workflowId);
        if (asOf == null) {
            throw new ResourceNotFoundException("Workflow not found");
        }
        return new RunCursor(1, asOf, null, null);
    }

    private List<MergedRun> canonicalRuns(
            int workspaceId, int workflowId, RunCursor cursor, int limit) {
        CanonicalPosition position = cursor.canonical();
        return workflowRunMapper.getPage(
                workspaceId,
                workflowId,
                cursor.asOf(),
                position == null ? null : position.startedAt(),
                position == null ? null : position.id(),
                limit).stream()
            .map(run -> new MergedRun(
                "canonical",
                run.getStartedAt(),
                run.getId(),
                1,
                canonicalSummary(run)))
            .toList();
    }

    private List<MergedRun> legacyRuns(
            int workspaceId, Integer ruleId, RunCursor cursor, int limit) {
        if (ruleId == null) {
            return List.of();
        }
        LegacyPosition position = cursor.legacy();
        return ruleMapper.getExecutionPage(
                workspaceId,
                ruleId,
                cursor.asOf(),
                position == null ? null : position.executedAt(),
                position == null ? null : position.id(),
                limit).stream()
            .map(execution -> new MergedRun(
                "legacy",
                legacyTimestamp(execution),
                execution.getId(),
                0,
                legacySummary(execution)))
            .toList();
    }

    WorkflowRunSummaryDto canonicalSummary(WorkflowRunView run) {
        return new WorkflowRunSummaryDto(
            "canonical-" + run.getId(),
            "canonical",
            run.getStatus(),
            null,
            new WorkflowRunSummaryDto.Version(
                run.getWorkflowVersionId(),
                run.getVersionNumber(),
                HexFormat.of().formatHex(run.getVersionDefinitionHash()),
                run.getVersionPublishedAt()),
            new WorkflowRunSummaryDto.Trigger(
                run.getTriggerType(),
                run.getTriggerEvent(),
                run.getRecordType(),
                run.getRecordId()),
            new WorkflowRunSummaryDto.RuntimeState(
                run.getWaitKind(),
                run.getResumeAt(),
                run.getCancelRequestedAt() != null),
            run.getStartedAt(),
            run.getFinishedAt(),
            duration(run.getStartedAt(), run.getFinishedAt()),
            failure(
                run.getFailureNodeId(), run.getFailureCode(), run.getFailureMessage()),
            true);
    }

    private WorkflowRunSummaryDto legacySummary(RuleExecution execution) {
        String status = normalizeLegacyStatus(execution.getStatus());
        LocalDateTime executedAt = legacyTimestamp(execution);
        return new WorkflowRunSummaryDto(
            "legacy-" + execution.getId(),
            "legacy",
            status,
            execution.getStatus(),
            null,
            new WorkflowRunSummaryDto.Trigger(
                null,
                null,
                execution.getTriggerEntityType(),
                execution.getTriggerEntityId()),
            null,
            executedAt,
            executedAt,
            0L,
            legacyFailure(execution.getStatus()),
            false);
    }

    private WorkflowRunDetailDto canonicalDetail(
            int workspaceId, int workflowId, long runId) {
        WorkflowRunView run = workflowRunMapper.getViewById(
            workspaceId, workflowId, runId);
        if (run == null) {
            throw runNotFound();
        }
        WorkflowVersion version = workflowVersionMapper.getById(
            workspaceId, workflowId, run.getWorkflowVersionId());
        if (version == null) {
            throw runNotFound();
        }
        CanonicalDraft canonical = canonicalizer.canonicalizeDraftJson(
            version.getName(),
            version.getDescription(),
            version.getRecordType(),
            version.getExecutionMode(),
            version.getDefinitionJson(),
            version.getCanvasJson());
        if (version.getDefinitionHash() == null
                || !MessageDigest.isEqual(
                    version.getDefinitionHash(), canonical.definitionHash())) {
            throw new WorkflowExecutionException(
                "definition_corrupt",
                "The pinned workflow definition failed its integrity check.",
                false);
        }
        List<WorkflowStepRunDto> path = workflowRunMapper.getSteps(workspaceId, runId).stream()
            .map(this::stepDto)
            .toList();
        return new WorkflowRunDetailDto(
            "canonical-" + runId,
            "canonical",
            workflowId,
            run.getStatus(),
            null,
            new WorkflowRunDetailDto.Version(
                version.getId(),
                version.getVersionNumber(),
                HexFormat.of().formatHex(version.getDefinitionHash()),
                version.getPublishedAt(),
                canonicalizer.parseDefinition(canonical.definitionJson()),
                canonicalizer.parseCanvas(canonical.canvasJson())),
            new WorkflowRunDetailDto.Execution(
                run.getExecutionMode(),
                run.getActorUserId(),
                run.getAttributionUserId()),
            new WorkflowRunSummaryDto.Trigger(
                run.getTriggerType(),
                run.getTriggerEvent(),
                run.getRecordType(),
                run.getRecordId()),
            new WorkflowRunSummaryDto.RuntimeState(
                run.getWaitKind(),
                run.getResumeAt(),
                run.getCancelRequestedAt() != null),
            run.getStartedAt(),
            run.getFinishedAt(),
            duration(run.getStartedAt(), run.getFinishedAt()),
            failure(
                run.getFailureNodeId(), run.getFailureCode(), run.getFailureMessage()),
            true,
            path);
    }

    private WorkflowRunDetailDto legacyDetail(int workflowId, RuleExecution execution) {
        LocalDateTime executedAt = legacyTimestamp(execution);
        return new WorkflowRunDetailDto(
            "legacy-" + execution.getId(),
            "legacy",
            workflowId,
            normalizeLegacyStatus(execution.getStatus()),
            execution.getStatus(),
            null,
            null,
            new WorkflowRunSummaryDto.Trigger(
                null,
                null,
                execution.getTriggerEntityType(),
                execution.getTriggerEntityId()),
            null,
            executedAt,
            executedAt,
            0L,
            legacyFailure(execution.getStatus()),
            false,
            List.of());
    }

    private WorkflowStepRunDto stepDto(WorkflowStepRun step) {
        return new WorkflowStepRunDto(
            step.getSequenceNumber(),
            step.getNodeId(),
            step.getNodeType(),
            step.getStatus(),
            step.getAttemptCount(),
            step.getRetrySafety(),
            step.getSelectedOutcome(),
            step.getSelectedEdgeId(),
            step.getNextNodeId(),
            step.getActionOutcome(),
            step.getActionReferenceId(),
            step.getStartedAt(),
            step.getFinishedAt(),
            duration(step.getStartedAt(), step.getFinishedAt()),
            failure(step.getNodeId(), step.getFailureCode(), step.getFailureMessage()));
    }

    private static WorkflowRunSummaryDto.Failure failure(
            String nodeId, String code, String message) {
        return code == null || message == null
            ? null : new WorkflowRunSummaryDto.Failure(nodeId, code, message);
    }

    private static WorkflowRunSummaryDto.Failure legacyFailure(String legacyStatus) {
        String normalized = normalizeLegacyStatus(legacyStatus);
        if (!"failed".equals(normalized) && !"partial".equals(normalized)) {
            return null;
        }
        String code = "partial".equals(normalize(legacyStatus))
            ? "legacy_partial" : "legacy_failed";
        String message = "partial".equals(normalize(legacyStatus))
            ? "Legacy execution completed only some actions; per-step diagnostics are unavailable."
            : "Legacy execution failed; per-step diagnostics are unavailable.";
        return new WorkflowRunSummaryDto.Failure(null, code, message);
    }

    static String normalizeLegacyStatus(String legacyStatus) {
        return switch (normalize(legacyStatus)) {
            case "matched" -> "succeeded";
            case "partial" -> "partial";
            case "skipped" -> "skipped";
            case "running" -> "running";
            case "failed" -> "failed";
            default -> "failed";
        };
    }

    private RunCursor decodeCursor(String value) {
        if (value.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length > MAX_CURSOR_LENGTH) {
                throw invalidCursor();
            }
            RunCursor cursor = objectMapper.readValue(
                new String(decoded, StandardCharsets.UTF_8), RunCursor.class);
            validateCursor(cursor);
            return cursor;
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCursor();
        }
    }

    private String encodeCursor(RunCursor cursor) {
        try {
            String json = objectMapper.writeValueAsString(cursor);
            String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
            if (encoded.length() > MAX_CURSOR_LENGTH) {
                throw invalidCursor();
            }
            return encoded;
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCursor();
        }
    }

    private static void validateCursor(RunCursor cursor) {
        if (cursor == null || cursor.v() != 1 || cursor.asOf() == null) {
            throw invalidCursor();
        }
        if (cursor.canonical() != null
                && (cursor.canonical().startedAt() == null
                    || cursor.canonical().id() <= 0)) {
            throw invalidCursor();
        }
        if (cursor.legacy() != null
                && (cursor.legacy().executedAt() == null || cursor.legacy().id() <= 0)) {
            throw invalidCursor();
        }
    }

    private static RunCursor advancedCursor(RunCursor cursor, List<MergedRun> page) {
        CanonicalPosition canonical = cursor.canonical();
        LegacyPosition legacy = cursor.legacy();
        for (MergedRun item : page) {
            if ("canonical".equals(item.source())) {
                canonical = new CanonicalPosition(item.occurredAt(), item.id());
            } else {
                legacy = new LegacyPosition(
                    item.occurredAt(), Math.toIntExact(item.id()));
            }
        }
        return new RunCursor(1, cursor.asOf(), canonical, legacy);
    }

    private Workflow requireWorkflow(int workspaceId, int workflowId) {
        Workflow workflow = workflowMapper.getById(workspaceId, workflowId);
        if (workflow == null) {
            throw new ResourceNotFoundException("Workflow not found");
        }
        return workflow;
    }

    private static Long duration(LocalDateTime start, LocalDateTime finish) {
        return start == null || finish == null
            ? null : Math.max(0L, Duration.between(start, finish).toMillis());
    }

    private static LocalDateTime legacyTimestamp(RuleExecution execution) {
        String value = execution.getExecutedAt();
        if (value == null || value.isBlank()) {
            throw new WorkflowExecutionException(
                "legacy_history_invalid",
                "Legacy execution history is unavailable.",
                false);
        }
        try {
            return LocalDateTime.parse(value.replace(' ', 'T'));
        } catch (java.time.format.DateTimeParseException exception) {
            throw new WorkflowExecutionException(
                "legacy_history_invalid",
                "Legacy execution history is unavailable.",
                false);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static BadRequestException invalidCursor() {
        return new BadRequestException("Invalid workflow run cursor");
    }

    private static ResourceNotFoundException runNotFound() {
        return new ResourceNotFoundException("Workflow run not found");
    }

    private record RunCursor(
        int v,
        LocalDateTime asOf,
        CanonicalPosition canonical,
        LegacyPosition legacy
    ) { }

    private record CanonicalPosition(LocalDateTime startedAt, long id) { }

    private record LegacyPosition(LocalDateTime executedAt, int id) { }

    private record MergedRun(
        String source,
        LocalDateTime occurredAt,
        long id,
        int sourceOrder,
        WorkflowRunSummaryDto dto
    ) { }

}
