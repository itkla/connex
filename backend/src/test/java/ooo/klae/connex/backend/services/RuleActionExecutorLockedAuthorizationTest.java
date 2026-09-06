package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.tenant.Permission;

class RuleActionExecutorLockedAuthorizationTest {

    @Test
    void durableLegacySendConsumesTheAlreadyLockedPermissionSnapshot() {
        CampaignTriggeredSendService triggeredSendService =
            mock(CampaignTriggeredSendService.class);
        RuleActionExecutor executor = new RuleActionExecutor(
            mock(TaskService.class),
            mock(ActivityService.class),
            mock(CompanyService.class),
            mock(PersonService.class),
            mock(LeadResponseSlaService.class),
            mock(DealService.class),
            mock(NoteService.class),
            mock(NotificationDelivery.class),
            mock(TagMapper.class),
            mock(DealDocumentMapper.class),
            triggeredSendService);
        RuleAction action = new RuleAction();
        action.setType("send_message");
        action.setCampaignMessageId(31);
        action.setCampaignMessageVersion(4);
        Set<Permission> permissions = Set.of(
            Permission.CAMPAIGN_MANAGE,
            Permission.CAMPAIGN_SEND,
            Permission.CONSENT_MANAGE);
        RuleFireContext context = new RuleFireContext(
            7, 11, "person", 19, 17, "event", 17, permissions);
        when(triggeredSendService.enrollWithLockedAuthorization(
                19, 31, 4, 17, permissions))
            .thenReturn(CampaignTriggeredSendService.EnrollmentResult.queued(41, 53));

        WorkflowActionResult result = executor.execute(action, context);

        assertEquals("delivery_queued", result.outcome());
        assertEquals(53L, result.referenceId());
        verify(triggeredSendService).enrollWithLockedAuthorization(
            19, 31, 4, 17, permissions);
        verify(triggeredSendService, never()).enroll(19, 31, 4);
    }
}
