package ooo.klae.connex.backend.services;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkflowManualOptionView;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowManualOptionsDto;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Discovers published workflow choices for record and bulk manual-launch surfaces. */
@Service
@RequiredArgsConstructor
public class WorkflowManualOptionsService {

    private static final Set<String> RECORD_TYPES = Set.of("person", "company", "deal");

    private final WorkflowMapper workflowMapper;
    private final WorkflowDraftCanonicalizer canonicalizer;
    private final WorkflowManualEligibilityService eligibilityService;
    private final WorkflowActionRetryPolicy retryPolicy;
    private final WorkflowRecordGuard recordGuard;
    private final WorkspaceService workspaceService;
    private final SystemActor systemActor;

    @Transactional(readOnly = true)
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowManualOptionsDto options(String recordTypeValue, Integer recordId) {
        String recordType = recordTypeValue == null
            ? ""
            : recordTypeValue.trim().toLowerCase(java.util.Locale.ROOT);
        if (!RECORD_TYPES.contains(recordType) || recordId != null && recordId < 1) {
            throw new BadRequestException("Manual workflow record selection is invalid");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int requesterId = workspaceService.getCurrentUserId();
        if (recordId != null) {
            try {
                recordGuard.requireAccessible(workspaceId, recordType, recordId);
            } catch (WorkflowExecutionException exception) {
                throw new ResourceNotFoundException("Record not found");
            }
        }
        List<WorkflowManualOptionsDto.Option> options = workflowMapper
            .listManualOptions(workspaceId, recordType)
            .stream()
            .map(candidate -> option(workspaceId, requesterId, candidate, recordId))
            .toList();
        return new WorkflowManualOptionsDto(
            recordType, recordId, createHref(recordType, recordId), options);
    }

    private WorkflowManualOptionsDto.Option option(
            int workspaceId,
            int requesterId,
            WorkflowManualOptionView candidate,
            Integer recordId) {
        WorkflowDefinition definition;
        try {
            definition = canonicalizer.parseDefinition(candidate.getDefinitionJson());
        } catch (BadRequestException exception) {
            return unavailableOption(workspaceId, candidate);
        }
        WorkflowManualEligibilityService.Evaluation evaluation = eligibilityService.evaluate(
            workspaceId, requesterId, candidate, definition, recordId);
        return new WorkflowManualOptionsDto.Option(
            candidate.getWorkflowId(),
            candidate.getWorkflowName(),
            candidate.getWorkflowVersionId(),
            candidate.getVersionNumber(),
            HexFormat.of().formatHex(candidate.getDefinitionHash()),
            definition.schemaVersion(),
            evaluation.manualEntryMode(),
            evaluation.reasons().isEmpty(),
            evaluation.reasons(),
            new WorkflowManualOptionsDto.Execution(
                candidate.getExecutionMode(),
                evaluation.actorUserId() > 0 ? evaluation.actorUserId() : null,
                actorLabel(workspaceId, candidate.getExecutionMode(), evaluation.actorUserId())),
            definition.inputs() == null ? List.of() : definition.inputs(),
            definition.nodes().stream()
                .filter(WorkflowNode.Action.class::isInstance)
                .map(WorkflowNode.Action.class::cast)
                .map(node -> new WorkflowManualOptionsDto.Action(
                    node.id(),
                    node.config().getType(),
                    retryPolicy.safety(node.config()).value()))
                .toList());
    }

    private WorkflowManualOptionsDto.Option unavailableOption(
            int workspaceId,
            WorkflowManualOptionView candidate) {
        Integer actorUserId = "system".equals(candidate.getExecutionMode())
            ? systemActor.user().getId()
            : candidate.getRunAsUserId();
        return new WorkflowManualOptionsDto.Option(
            candidate.getWorkflowId(),
            candidate.getWorkflowName(),
            candidate.getWorkflowVersionId(),
            candidate.getVersionNumber(),
            candidate.getDefinitionHash() == null
                ? null : HexFormat.of().formatHex(candidate.getDefinitionHash()),
            0,
            "denied",
            false,
            List.of("configuration_unavailable"),
            new WorkflowManualOptionsDto.Execution(
                candidate.getExecutionMode(),
                actorUserId,
                actorUserId == null
                    ? null : actorLabel(
                        workspaceId,
                        candidate.getExecutionMode(),
                        actorUserId)),
            List.of(),
            List.of());
    }

    private String actorLabel(int workspaceId, String executionMode, int actorUserId) {
        if ("system".equals(executionMode) || actorUserId < 1) return null;
        return workspaceService.getMembers(workspaceId).stream()
            .filter(member -> member.getId() == actorUserId)
            .map(WorkflowManualOptionsService::memberLabel)
            .findFirst()
            .orElse(null);
    }

    private static String memberLabel(User member) {
        return member.getDisplayName() != null && !member.getDisplayName().isBlank()
            ? member.getDisplayName().trim()
            : member.getUsername();
    }

    private static String createHref(String recordType, Integer recordId) {
        String href = "/workflows/new?recordType=" + recordType + "&start=manual";
        if (recordId == null) return href;
        String recordPath = switch (recordType) {
            case "person" -> "/records/contacts/" + recordId;
            case "company" -> "/records/companies/" + recordId;
            default -> "/records/deals/" + recordId;
        };
        return href + "&returnTo=" + URLEncoder.encode(recordPath, StandardCharsets.UTF_8);
    }
}
