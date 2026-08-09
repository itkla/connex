package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleDto;
import ooo.klae.connex.backend.dto.RuleExecutionDto;
import ooo.klae.connex.backend.dto.RuleExecutionSummaryDto;
import ooo.klae.connex.backend.dto.RulePreviewDto;
import ooo.klae.connex.backend.dto.RulePreviewRequest;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Legacy compatibility application layer for automation rules. Reads project {@code rule} and
 * {@code rule_execution}; mutations are scoped to the active workspace, gated by
 * {@link Permission#RULE_MANAGE}, and delegated to {@link LegacyRuleWorkflowService}, which owns the
 * transactional workflow aggregate update. {@code system}-mode mutations additionally require the
 * admin tier. The typed trigger, optional WHEN condition, and THEN actions are validated through
 * {@link RuleDefinitionValidator} and decoded through {@link RuleDefinitionCodec}.
 */
@Service
@RequiredArgsConstructor
public class RuleService {

    private final RuleMapper ruleMapper;
    private final SegmentService segmentService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;
    private final LegacyRuleWorkflowService legacyRuleWorkflowService;
    private final RuleDefinitionValidator definitionValidator;
    private final RuleDefinitionCodec definitionCodec;

    private static final int PREVIEW_SAMPLE = 25;

    @RequirePermission(Permission.RULE_MANAGE)
    public List<RuleDto> list() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Rule> rules = ruleMapper.getByWorkspace(workspaceId);
        if (rules.isEmpty()) {
            return List.of();
        }
        Map<Integer, RuleExecution> latestByRuleId = ruleMapper
            .getLatestExecutionsByWorkspace(workspaceId)
            .stream()
            .collect(Collectors.toMap(RuleExecution::getRuleId, Function.identity()));
        return rules.stream()
            .map(rule -> toDto(rule, latestByRuleId.get(rule.getId())))
            .toList();
    }

    @RequirePermission(Permission.RULE_MANAGE)
    public RuleDto getById(int id) {
        return toDto(requireRule(id));
    }

    @RequirePermission(Permission.RULE_MANAGE)
    public List<RuleExecutionDto> executions(int id) {
        requireRule(id);
        return ruleMapper.getExecutionsByRule(workspaceService.getCurrentWorkspaceId(), id, 50)
            .stream()
            .map(this::toExecutionDto)
            .toList();
    }

    /**
     * Dry-runs a WHEN condition over the active workspace's records of {@code recordType}, returning
     * the total match count and a bounded sample — without creating or firing a rule.
     */
    @RequirePermission(Permission.RULE_MANAGE)
    public RulePreviewDto preview(RulePreviewRequest request) {
        String recordType = definitionValidator.validatePreview(request);
        List<Integer> ids = segmentService.evaluate(recordType, request.getCondition());
        List<Integer> sampleIds = ids.stream().sorted().limit(PREVIEW_SAMPLE).toList();
        return new RulePreviewDto(ids.size(), segmentService.labels(recordType, sampleIds));
    }

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public RuleDto create(RuleRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = authService.getCurrentUser().getId();
        Rule rule = legacyRuleWorkflowService.create(workspaceId, userId, request);
        auditService.record("rule.create", "rule", rule.getId(), rule.getName(),
            "Created rule " + rule.getName(), auditService.singleChange("enabled", null, rule.isEnabled()));
        return toDto(rule);
    }

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public RuleDto update(int id, RuleRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        Rule rule = legacyRuleWorkflowService.update(workspaceId, actorId, id, request);
        auditService.record("rule.update", "rule", id, rule.getName(),
            "Updated rule " + rule.getName(), null);
        return toDto(rule);
    }

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        Rule rule = legacyRuleWorkflowService.delete(workspaceId, actorId, id);
        auditService.record("rule.archive", "rule", id, rule.getName(),
            "Archived rule " + rule.getName(), null);
    }

    private Rule requireRule(int id) {
        Rule rule = ruleMapper.getById(workspaceService.getCurrentWorkspaceId(), id);
        if (rule == null) {
            throw new ResourceNotFoundException("Rule not found with id: " + id);
        }
        return rule;
    }

    private RuleDto toDto(Rule rule) {
        return toDto(rule, null);
    }

    private RuleDto toDto(Rule rule, RuleExecution latestExecution) {
        RuleDto dto = new RuleDto();
        dto.setId(rule.getId());
        dto.setName(rule.getName());
        dto.setDescription(rule.getDescription());
        dto.setEnabled(rule.isEnabled());
        dto.setRecordType(rule.getRecordType());
        dto.setTrigger(definitionCodec.parse(rule.getTriggerConfig(), RuleTrigger.class));
        dto.setCondition(rule.getConditionJson() == null
            ? null
            : definitionCodec.parse(rule.getConditionJson(), SegmentDefinition.class));
        dto.setActions(List.of(definitionCodec.parse(rule.getActionsJson(), RuleAction[].class)));
        dto.setExecutionMode(rule.getExecutionMode());
        dto.setRunAsUserId(rule.getRunAsUserId());
        dto.setCreatedById(rule.getCreatedById());
        dto.setCreatedAt(rule.getCreatedAt());
        dto.setUpdatedAt(rule.getUpdatedAt());
        dto.setLatestExecution(latestExecution == null
            ? null
            : new RuleExecutionSummaryDto(
                latestExecution.getStatus(), latestExecution.getExecutedAt()));
        return dto;
    }

    private RuleExecutionDto toExecutionDto(RuleExecution execution) {
        return new RuleExecutionDto(
            execution.getId(),
            execution.getTriggerEntityType(),
            execution.getTriggerEntityId(),
            execution.getStatus(),
            execution.getExecutedAt());
    }

}
