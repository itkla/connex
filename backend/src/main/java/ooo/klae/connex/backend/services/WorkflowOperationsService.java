package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowBacklogView;
import ooo.klae.connex.backend.beans.WorkflowOperationsRunView;
import ooo.klae.connex.backend.beans.WorkflowOperationsSummaryView;
import ooo.klae.connex.backend.beans.WorkflowRecipeOrigin;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowInterventionDto;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.dto.WorkflowOperationsDetailDto;
import ooo.klae.connex.backend.dto.WorkflowOperationsRunDto;
import ooo.klae.connex.backend.dto.WorkflowOperationsRunPageDto;
import ooo.klae.connex.backend.dto.WorkflowOperationsSummaryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Read-only, support-safe workflow health and intervention projections. */
@Service
@RequiredArgsConstructor
public class WorkflowOperationsService {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_CURSOR_LENGTH = 256;
    private static final Set<String> RUN_STATUSES = Set.of(
        "queued", "running", "waiting", "succeeded", "failed", "skipped",
        "cancelled", "intervention_required");
    private static final Set<String> FAILURE_CATEGORIES = Set.of(
        "actor", "permission", "reference", "retry", "configuration", "execution");

    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final WorkflowOperationsMapper operationsMapper;
    private final WorkflowDraftCanonicalizer canonicalizer;
    private final WorkflowRunReadService runReadService;
    private final WorkspaceService workspaceService;

    @Transactional(readOnly = true)
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowOperationsSummaryDto summary() {
        WorkflowOperationsSummaryView view = operationsMapper.getSummary(
            workspaceService.getCurrentWorkspaceId());
        return new WorkflowOperationsSummaryDto(
            view.getWorkflowCount(),
            view.getHealthyCount(),
            view.getPausedCount(),
            view.getDisabledCount(),
            view.getInterventionRequiredCount(),
            view.getQueuedCount(),
            view.getWaitingCount(),
            view.getOverdueCount(),
            view.getOpenInterventionCount(),
            view.getRecentFailureCount());
    }

    @Transactional(readOnly = true)
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowOperationsRunPageDto runs(
            String status,
            String failureCategory,
            Integer ownerId,
            Integer requestedLimit,
            String cursorValue) {
        validateFilter(status, RUN_STATUSES, "run status");
        validateFilter(failureCategory, FAILURE_CATEGORIES, "failure category");
        if (ownerId != null && ownerId < 1) {
            throw new BadRequestException("Intervention owner id must be positive");
        }
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BadRequestException("Operations page limit must be between 1 and 100");
        }
        OperationsCursor cursor = decodeCursor(cursorValue);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<WorkflowOperationsRunView> rows = operationsMapper.getOperationsRuns(
            workspaceId,
            status,
            failureCategory,
            ownerId,
            cursor == null ? null : cursor.startedAt(),
            cursor == null ? null : cursor.id(),
            limit + 1);
        boolean hasNext = rows.size() > limit;
        List<WorkflowOperationsRunDto> items = rows.stream()
            .limit(limit)
            .map(this::runDto)
            .toList();
        WorkflowOperationsRunView last = hasNext ? rows.get(limit - 1) : null;
        return new WorkflowOperationsRunPageDto(
            items,
            last == null ? null : encodeCursor(last.getStartedAt(), last.getId()));
    }

    @Transactional(readOnly = true)
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowOperationsDetailDto workflow(int workflowId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Workflow workflow = workflowMapper.getById(workspaceId, workflowId);
        if (workflow == null) {
            throw new ResourceNotFoundException("Workflow not found");
        }
        List<WorkflowVersion> versions = workflowVersionMapper.listByWorkflow(
            workspaceId, workflowId);
        WorkflowBacklogView backlog = operationsMapper.getBacklog(workspaceId, workflowId);
        List<WorkflowInterventionDto> interventions = operationsMapper
            .getOpenInterventionsByWorkflow(workspaceId, workflowId, 50).stream()
            .map(WorkflowInterventionDto::from)
            .toList();
        WorkflowRecipeOrigin recipeOrigin = operationsMapper.getRecipeOrigin(
            workspaceId, workflowId);
        WorkflowOperationsDetailDto.Backlog backlogDto = backlogDto(backlog);
        return new WorkflowOperationsDetailDto(
            recipeOrigin == null ? null : recipeOrigin.getRecipeKey(),
            new WorkflowOperationsDetailDto.WorkflowInfo(
                workflow.getId(),
                workflow.getName(),
                workflow.isEnabled(),
                workflow.getArchivedAt(),
                workflow.getIntakePausedAt(),
                workflow.getIntakePausedById(),
                workflow.getRuntimeOwner()),
            health(workflow, backlogDto, interventions),
            activeVersion(workflow, versions),
            definitionChanges(versions),
            backlogDto,
            interventions);
    }

    private WorkflowOperationsRunDto runDto(WorkflowOperationsRunView run) {
        WorkflowInterventionDto intervention = null;
        if (run.getInterventionId() != null) {
            intervention = new WorkflowInterventionDto(
                run.getInterventionId(),
                "canonical-" + run.getId(),
                run.getInterventionStepNodeId(),
                run.getInterventionCategory(),
                run.getInterventionReasonCode(),
                run.getInterventionOwnerUserId(),
                run.getInterventionStatus(),
                run.getInterventionSourceVersion(),
                run.getInterventionCreatedAt(),
                run.getInterventionUpdatedAt());
        }
        return new WorkflowOperationsRunDto(
            run.getWorkflowId(),
            run.getWorkflowName(),
            run.getRecipeKey(),
            runReadService.canonicalSummary(run),
            run.getInterventionCategory() != null
                ? run.getInterventionCategory()
                : run.getFailureCode() == null
                    ? null
                    : WorkflowInterventionRecorder.failureCategory(run.getFailureCode()),
            intervention);
    }

    private WorkflowOperationsDetailDto.ActiveVersion activeVersion(
            Workflow workflow,
            List<WorkflowVersion> versions) {
        if (workflow.getActiveVersionId() == null) {
            return null;
        }
        return versions.stream()
            .filter(version -> version.getId() == workflow.getActiveVersionId())
            .findFirst()
            .map(version -> new WorkflowOperationsDetailDto.ActiveVersion(
                version.getId(),
                version.getVersionNumber(),
                HexFormat.of().formatHex(version.getDefinitionHash()),
                version.getPublishedAt(),
                version.getPublishedById()))
            .orElse(null);
    }

    private List<WorkflowOperationsDetailDto.DefinitionChange> definitionChanges(
            List<WorkflowVersion> versions) {
        List<WorkflowOperationsDetailDto.DefinitionChange> changes = new ArrayList<>();
        int bound = Math.min(versions.size(), 6);
        for (int index = 0; index < bound; index++) {
            WorkflowVersion current = versions.get(index);
            WorkflowVersion prior = index + 1 < versions.size() ? versions.get(index + 1) : null;
            NodeDiff diff = diff(current, prior);
            changes.add(new WorkflowOperationsDetailDto.DefinitionChange(
                prior == null ? null : prior.getVersionNumber(),
                current.getVersionNumber(),
                current.getPublishedAt(),
                current.getPublishedById(),
                diff.added(),
                diff.removed(),
                diff.changed()));
        }
        return List.copyOf(changes);
    }

    private NodeDiff diff(WorkflowVersion current, WorkflowVersion prior) {
        Map<String, WorkflowNode> currentNodes = nodes(current);
        if (prior == null) {
            return new NodeDiff(
                currentNodes.keySet().stream().sorted().toList(),
                List.of(),
                List.of());
        }
        Map<String, WorkflowNode> priorNodes = nodes(prior);
        Set<String> currentIds = new HashSet<>(currentNodes.keySet());
        Set<String> priorIds = new HashSet<>(priorNodes.keySet());
        List<String> added = currentIds.stream()
            .filter(id -> !priorIds.contains(id)).sorted().toList();
        List<String> removed = priorIds.stream()
            .filter(id -> !currentIds.contains(id)).sorted().toList();
        List<String> changed = currentIds.stream()
            .filter(priorIds::contains)
            .filter(id -> !currentNodes.get(id).equals(priorNodes.get(id)))
            .sorted()
            .toList();
        return new NodeDiff(added, removed, changed);
    }

    private Map<String, WorkflowNode> nodes(WorkflowVersion version) {
        WorkflowDefinition definition = canonicalizer.parseDefinition(version.getDefinitionJson());
        Map<String, WorkflowNode> nodes = new LinkedHashMap<>();
        for (WorkflowNode node : definition.nodes()) {
            nodes.put(node.id(), node);
        }
        return nodes;
    }

    private WorkflowOperationsDetailDto.Health health(
            Workflow workflow,
            WorkflowOperationsDetailDto.Backlog backlog,
            List<WorkflowInterventionDto> interventions) {
        List<String> signals = new ArrayList<>();
        if (workflow.getArchivedAt() != null) {
            signals.add("workflow_archived");
        }
        if (!workflow.isEnabled()) {
            signals.add("workflow_disabled");
        }
        if (workflow.getIntakePausedAt() != null) {
            signals.add("intake_paused");
        }
        if (!interventions.isEmpty()) {
            signals.add("open_intervention");
        }
        if (backlog.queuedCount() > 0) {
            signals.add("queued_backlog");
        }
        if (backlog.waitingCount() > 0) {
            signals.add("waiting_backlog");
        }
        if (backlog.overdueCount() > 0) {
            signals.add("overdue_backlog");
        }
        if (backlog.recentFailureCount() > 0) {
            signals.add("recent_failure");
        }
        String state;
        if (workflow.getArchivedAt() != null) {
            state = "archived";
        } else if (!workflow.isEnabled()) {
            state = "disabled";
        } else if (workflow.getIntakePausedAt() != null) {
            state = "paused";
        } else if (!interventions.isEmpty()) {
            state = "intervention_required";
        } else if (backlog.overdueCount() > 0) {
            state = "backlogged";
        } else if (backlog.recentFailureCount() > 0) {
            state = "degraded";
        } else {
            state = "healthy";
        }
        return new WorkflowOperationsDetailDto.Health(state, List.copyOf(signals));
    }

    private static WorkflowOperationsDetailDto.Backlog backlogDto(WorkflowBacklogView backlog) {
        if (backlog == null) {
            return new WorkflowOperationsDetailDto.Backlog(0, null, 0, 0, 0, null, 0);
        }
        return new WorkflowOperationsDetailDto.Backlog(
            backlog.getQueuedCount(),
            backlog.getOldestQueuedAt(),
            backlog.getWaitingCount(),
            backlog.getDueNowCount(),
            backlog.getOverdueCount(),
            backlog.getNextResumeAt(),
            backlog.getRecentFailureCount());
    }

    private static void validateFilter(String value, Set<String> allowed, String label) {
        if (value != null && !value.isBlank() && !allowed.contains(value)) {
            throw new BadRequestException("Unknown workflow " + label);
        }
    }

    private static OperationsCursor decodeCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > MAX_CURSOR_LENGTH) {
            throw new BadRequestException("Invalid operations cursor");
        }
        try {
            String decoded = new String(
                Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            if (separator < 1) {
                throw new IllegalArgumentException("cursor");
            }
            LocalDateTime startedAt = LocalDateTime.parse(decoded.substring(0, separator));
            long id = Long.parseLong(decoded.substring(separator + 1));
            if (id < 1) {
                throw new IllegalArgumentException("cursor");
            }
            return new OperationsCursor(startedAt, id);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid operations cursor");
        }
    }

    private static String encodeCursor(LocalDateTime startedAt, long id) {
        String raw = startedAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record OperationsCursor(LocalDateTime startedAt, long id) { }

    private record NodeDiff(
        List<String> added,
        List<String> removed,
        List<String> changed
    ) { }
}
