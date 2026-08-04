package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;

/** Derives bounded compatibility metadata without flattening canonical traversal semantics. */
@Component
@RequiredArgsConstructor
public class WorkflowVersionProjection {

    private final RuleDefinitionCodec definitionCodec;

    public Rule project(
            Workflow workflow, CanonicalDraft draft, CompiledWorkflow compiled) {
        WorkflowNode.Trigger trigger = requireEntryTrigger(compiled);
        SegmentDefinition enrollment = enrollmentCondition(compiled);
        List<RuleAction> actions = new ArrayList<>();
        for (String nodeId : compiled.topologicalOrder()) {
            WorkflowNode node = compiled.node(nodeId);
            if (node instanceof WorkflowNode.Action action) {
                actions.add(action.config());
            }
        }
        Rule projection = new Rule();
        projection.setId(workflow.getLegacyRuleId() == null ? 0 : workflow.getLegacyRuleId());
        projection.setWorkspaceId(workflow.getWorkspaceId());
        projection.setName(draft.name());
        projection.setDescription(draft.description());
        projection.setEnabled(workflow.isEnabled());
        projection.setRecordType(draft.recordType());
        projection.setTriggerType(normalize(trigger.config().getType()));
        projection.setTriggerConfig(definitionCodec.serialize(trigger.config()));
        projection.setConditionJson(enrollment == null
            ? null : definitionCodec.serialize(enrollment));
        projection.setActionsJson(definitionCodec.serialize(List.copyOf(actions)));
        projection.setExecutionMode(draft.executionMode());
        projection.setRunAsUserId(workflow.getDraftRunAsUserId());
        projection.setCreatedById(workflow.getCreatedById());
        return projection;
    }

    private static WorkflowNode.Trigger requireEntryTrigger(CompiledWorkflow compiled) {
        WorkflowNode entry = compiled.node(compiled.entryNodeId());
        if (!(entry instanceof WorkflowNode.Trigger trigger)) {
            throw new BadRequestException(
                "Compiled workflow entry node must reference the trigger node");
        }
        if (trigger.config() == null) {
            throw new BadRequestException(
                "Compiled workflow trigger configuration is required");
        }
        return trigger;
    }

    private static SegmentDefinition enrollmentCondition(CompiledWorkflow compiled) {
        String nodeId = compiled.enrollmentConditionNodeId();
        if (nodeId == null) {
            return null;
        }
        WorkflowNode node = compiled.node(nodeId);
        if (!(node instanceof WorkflowNode.Condition condition)) {
            throw new BadRequestException(
                "Compiled workflow enrollment node must reference a condition node");
        }
        if (condition.config() == null) {
            throw new BadRequestException(
                "Compiled workflow enrollment condition configuration is required");
        }
        return condition.config();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
