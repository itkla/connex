package ooo.klae.connex.backend.services;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.CampaignMessage;
import ooo.klae.connex.backend.beans.CampaignMessageRevision;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.delivery.DeliveryProviderException;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.CampaignMessageMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.tenant.Permission;

/** Read-only action permission and mutable-reference preflight shared with simulation. */
@Service
@RequiredArgsConstructor
public class WorkflowActionGuard {

    private final WorkspaceService workspaceService;
    private final RuleDefinitionValidator definitionValidator;
    private final TagMapper tagMapper;
    private final PipelineMapper pipelineMapper;
    private final DealMapper dealMapper;
    private final CampaignMessageMapper campaignMessageMapper;
    private final CampaignSendMapper campaignSendMapper;
    private final AudienceEligibilityService audienceEligibilityService;
    private final CapabilityRegistry capabilityRegistry;
    private final DeliveryProviderConfigService deliveryProviderConfigService;
    private final WorkflowTriggeredSendGate triggeredSendGate;

    public WorkflowDiagnosticDto blocker(
            int workspaceId,
            int actorUserId,
            String recordType,
            int recordId,
            String nodeId,
            RuleAction action) {
        Set<Permission> required = definitionValidator.actionPermissions(action, recordType);
        Set<Permission> permissions = workspaceService.permissionsFor(workspaceId, actorUserId);
        Permission missing = required.stream()
                .filter(permission -> !permissions.contains(permission))
                .sorted()
                .findFirst()
                .orElse(null);
        if (missing != null) {
            return diagnostic(
                WorkflowDiagnosticCode.ACTION_PERMISSION_MISSING,
                nodeId,
                null,
                Map.of("permission", missing.name()));
        }
        String type = normalize(action.getType());
        if (("add_tag".equals(type) || "remove_tag".equals(type))
                && tagMapper.getTagById(workspaceId, action.getTagId()) == null) {
            return diagnostic(
                WorkflowDiagnosticCode.ACTION_TAG_UNAVAILABLE,
                nodeId,
                "config.tagId",
                Map.of());
        }
        if ("assign_owner".equals(type)
                && workspaceService.getRole(workspaceId, action.getTargetUserId()) == null) {
            return diagnostic(
                WorkflowDiagnosticCode.ACTION_TARGET_MEMBER_UNAVAILABLE,
                nodeId,
                "config.targetUserId",
                Map.of());
        }
        if ("change_stage".equals(type)) {
            Stage stage = pipelineMapper.getStageById(workspaceId, action.getTargetStageId());
            if (stage == null || stage.getPipeline() == null) {
                return diagnostic(
                    WorkflowDiagnosticCode.ACTION_STAGE_UNAVAILABLE,
                    nodeId,
                    "config.targetStageId",
                    Map.of());
            }
            Deal deal = dealMapper.getDealById(workspaceId, recordId);
            if (deal == null || deal.getPipelineId() == null
                    || deal.getPipelineId() != stage.getPipeline().getId()) {
                return diagnostic(
                    WorkflowDiagnosticCode.ACTION_STAGE_PIPELINE_MISMATCH,
                    nodeId,
                    "config.targetStageId",
                    Map.of());
            }
        }
        if ("send_message".equals(type)) {
            return triggeredSendBlocker(workspaceId, recordId, nodeId, action);
        }
        return null;
    }

    private WorkflowDiagnosticDto triggeredSendBlocker(
            int workspaceId,
            int personId,
            String nodeId,
            RuleAction action) {
        if (action.getCampaignMessageId() == null) {
            return diagnostic(
                    WorkflowDiagnosticCode.ACTION_CAMPAIGN_MESSAGE_UNAVAILABLE,
                    nodeId,
                    "config.campaignMessageId",
                    Map.of());
        }
        if (action.getCampaignMessageVersion() == null) {
            return diagnostic(
                    WorkflowDiagnosticCode.ACTION_CAMPAIGN_MESSAGE_REVISION_UNAVAILABLE,
                    nodeId,
                    "config.campaignMessageVersion",
                    Map.of());
        }
        if (!triggeredSendGate.enabled()
                || !capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)) {
            return diagnostic(
                    WorkflowDiagnosticCode.ACTION_DELIVERY_CAPABILITY_UNAVAILABLE,
                    nodeId,
                    null,
                    Map.of());
        }
        CampaignMessage message = campaignMessageMapper.getMessage(
                workspaceId, action.getCampaignMessageId());
        if (message == null) {
            return diagnostic(
                    WorkflowDiagnosticCode.ACTION_CAMPAIGN_MESSAGE_UNAVAILABLE,
                    nodeId,
                    "config.campaignMessageId",
                    Map.of());
        }
        CampaignMessageRevision revision = campaignMessageMapper.getRevision(
                workspaceId,
                action.getCampaignMessageId(),
                action.getCampaignMessageVersion());
        if (revision == null) {
            return diagnostic(
                    WorkflowDiagnosticCode.ACTION_CAMPAIGN_MESSAGE_REVISION_UNAVAILABLE,
                    nodeId,
                    "config.campaignMessageVersion",
                    Map.of());
        }
        DeliveryChannel channel;
        try {
            channel = DeliveryChannel.fromToken(message.getChannel());
        } catch (DeliveryProviderException exception) {
            return diagnostic(
                    WorkflowDiagnosticCode.ACTION_DELIVERY_TRANSPORT_UNAVAILABLE,
                    nodeId,
                    "config.campaignMessageId",
                    Map.of());
        }
        if ((channel != DeliveryChannel.EMAIL && channel != DeliveryChannel.SMS)
                || !deliveryProviderConfigService.isReady(workspaceId, channel)) {
            return diagnostic(
                    WorkflowDiagnosticCode.ACTION_DELIVERY_TRANSPORT_UNAVAILABLE,
                    nodeId,
                    "config.campaignMessageId",
                    Map.of());
        }
        AudienceEligibilityService.AudienceClassification eligibility =
                audienceEligibilityService.classify(
                    workspaceId, java.util.List.of(personId), channel.token(), "marketing");
        String exclusionReason = eligibility.reasonFor(personId);
        if ("restricted".equals(exclusionReason)) {
            return diagnostic(
                    WorkflowDiagnosticCode.ACTION_TRIGGERED_SEND_UNAVAILABLE,
                    nodeId,
                    null,
                    Map.of("status", exclusionReason));
        }
        if ("no_address".equals(exclusionReason)) {
            return diagnostic(
                    WorkflowDiagnosticCode.ACTION_RECIPIENT_ADDRESS_UNAVAILABLE,
                    nodeId,
                    null,
                    Map.of());
        }
        CampaignSend send = campaignSendMapper.getTriggeredSend(
                workspaceId,
                action.getCampaignMessageId(),
                action.getCampaignMessageVersion());
        if (send != null
                && !"triggered".equals(send.getStatus())) {
            return diagnostic(
                    WorkflowDiagnosticCode.ACTION_TRIGGERED_SEND_UNAVAILABLE,
                    nodeId,
                    null,
                    Map.of("status", send.getStatus()));
        }
        return null;
    }

    private static WorkflowDiagnosticDto diagnostic(
            WorkflowDiagnosticCode code,
            String nodeId,
            String fieldPath,
            Map<String, String> params) {
        return new WorkflowDiagnosticDto(code, nodeId, null, fieldPath, params);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
