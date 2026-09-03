package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;
import ooo.klae.connex.backend.beans.CampaignMessage;
import ooo.klae.connex.backend.beans.CampaignMessageRevision;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.CampaignMessageMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class WorkflowActionGuardTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private RuleDefinitionValidator definitionValidator;
    @Mock private TagMapper tagMapper;
    @Mock private PipelineMapper pipelineMapper;
    @Mock private DealMapper dealMapper;
    @Mock private CampaignMessageMapper campaignMessageMapper;
    @Mock private CampaignSendMapper campaignSendMapper;
    @Mock private AudienceEligibilityService audienceEligibilityService;
    @Mock private CapabilityRegistry capabilityRegistry;
    @Mock private DeliveryProviderConfigService deliveryProviderConfigService;
    @Mock private WorkflowTriggeredSendGate triggeredSendGate;

    @InjectMocks private WorkflowActionGuard guard;

    @Test
    void removeTagFailsClosedWhenThePublishedTagReferenceWasDeleted() {
        RuleAction action = new RuleAction();
        action.setType("remove_tag");
        action.setTagId(29);
        when(definitionValidator.actionPermissions(action, "person"))
            .thenReturn(Set.of(Permission.PERSON_UPDATE));
        when(workspaceService.permissionsFor(7, 17))
            .thenReturn(Set.of(Permission.PERSON_UPDATE));
        when(tagMapper.getTagById(7, 29)).thenReturn(null);

        WorkflowDiagnosticDto diagnostic = guard.blocker(
            7, 17, "person", 41, "remove-tag", action);

        assertEquals(WorkflowDiagnosticCode.ACTION_TAG_UNAVAILABLE, diagnostic.code());
    }

    @Test
    void sendMessageReportsTheMissingPermissionDeterministically() {
        RuleAction action = sendMessage();
        when(definitionValidator.actionPermissions(action, "person"))
            .thenReturn(Set.of(
                Permission.CAMPAIGN_MANAGE,
                Permission.CAMPAIGN_SEND,
                Permission.CONSENT_MANAGE));
        when(workspaceService.permissionsFor(7, 17))
            .thenReturn(Set.of(Permission.CAMPAIGN_MANAGE, Permission.CAMPAIGN_SEND));

        WorkflowDiagnosticDto diagnostic = guard.blocker(
            7, 17, "person", 41, "send-message", action);

        assertEquals(WorkflowDiagnosticCode.ACTION_PERMISSION_MISSING, diagnostic.code());
        assertEquals(Permission.CONSENT_MANAGE.name(), diagnostic.params().get("permission"));
    }

    @Test
    void sendMessageFailsClosedWhenTheRevisionWasDeleted() {
        RuleAction action = sendMessage();
        CampaignMessage message = new CampaignMessage();
        message.setId(29);
        message.setChannel("email");
        when(definitionValidator.actionPermissions(action, "person"))
            .thenReturn(Set.of(
                Permission.CAMPAIGN_MANAGE,
                Permission.CAMPAIGN_SEND,
                Permission.CONSENT_MANAGE));
        when(workspaceService.permissionsFor(7, 17))
            .thenReturn(Set.of(
                Permission.CAMPAIGN_MANAGE,
                Permission.CAMPAIGN_SEND,
                Permission.CONSENT_MANAGE));
        when(triggeredSendGate.enabled()).thenReturn(true);
        when(capabilityRegistry.isAvailable(
            ooo.klae.connex.backend.capability.Capability.CAMPAIGN_DELIVERY)).thenReturn(true);
        when(campaignMessageMapper.getMessage(7, 29)).thenReturn(message);
        when(campaignMessageMapper.getRevision(7, 29, 3)).thenReturn(null);

        WorkflowDiagnosticDto diagnostic = guard.blocker(
            7, 17, "person", 41, "send-message", action);

        assertEquals(
            WorkflowDiagnosticCode.ACTION_CAMPAIGN_MESSAGE_REVISION_UNAVAILABLE,
            diagnostic.code());
    }

    @Test
    void sendMessageClassifiesRestrictionBeforeAnyAddressSpecificGuard() {
        RuleAction action = sendMessage();
        CampaignMessage message = new CampaignMessage();
        message.setId(29);
        message.setChannel("email");
        when(definitionValidator.actionPermissions(action, "person"))
            .thenReturn(Set.of(
                Permission.CAMPAIGN_MANAGE,
                Permission.CAMPAIGN_SEND,
                Permission.CONSENT_MANAGE));
        when(workspaceService.permissionsFor(7, 17))
            .thenReturn(Set.of(
                Permission.CAMPAIGN_MANAGE,
                Permission.CAMPAIGN_SEND,
                Permission.CONSENT_MANAGE));
        when(triggeredSendGate.enabled()).thenReturn(true);
        when(capabilityRegistry.isAvailable(
            ooo.klae.connex.backend.capability.Capability.CAMPAIGN_DELIVERY)).thenReturn(true);
        when(campaignMessageMapper.getMessage(7, 29)).thenReturn(message);
        when(campaignMessageMapper.getRevision(7, 29, 3))
            .thenReturn(new CampaignMessageRevision());
        when(deliveryProviderConfigService.isReady(7,
            ooo.klae.connex.backend.delivery.DeliveryChannel.EMAIL)).thenReturn(true);
        when(audienceEligibilityService.classify(7, java.util.List.of(41), "email", "marketing"))
            .thenReturn(new AudienceEligibilityService.AudienceClassification(
                Set.of(), Set.of(41), Set.of(), Set.of(), java.util.List.of()));

        WorkflowDiagnosticDto diagnostic = guard.blocker(
            7, 17, "person", 41, "send-message", action);

        assertEquals(WorkflowDiagnosticCode.ACTION_TRIGGERED_SEND_UNAVAILABLE, diagnostic.code());
        assertEquals("restricted", diagnostic.params().get("status"));
    }

    private static RuleAction sendMessage() {
        RuleAction action = new RuleAction();
        action.setType("send_message");
        action.setCampaignMessageId(29);
        action.setCampaignMessageVersion(3);
        return action;
    }
}
