package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignDeliveryEvent;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.ContactChannelConsentRequest;
import ooo.klae.connex.backend.dto.DeliveryUnsubscribeDto;
import ooo.klae.connex.backend.dto.SuppressionEntryRequest;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;

/**
 * Unit tests for the public unsubscribe flow: the delivery's own workspace scope is entered before
 * any workspace-scoped statement runs and before the write transaction opens (#994), the token alone
 * resolves the workspace, and repeating the request is idempotent.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryUnsubscribeServiceTest {

    private static final String TOKEN = "a".repeat(64);
    private static final int WORKSPACE = 11;
    private static final int DELIVERY_ID = 500;
    private static final int SEND_ID = 300;
    private static final int PERSON_ID = 42;

    @Mock private CampaignDeliveryMapper campaignDeliveryMapper;
    @Mock private CampaignSendMapper campaignSendMapper;
    @Mock private SuppressionService suppressionService;
    @Mock private ConsentService consentService;
    @Mock private SystemActor systemActor;
    @Mock private AutomationExecutor automationExecutor;
    @Mock private TransactionTemplate transactionTemplate;

    private DeliveryUnsubscribeService service() {
        return new DeliveryUnsubscribeService(campaignDeliveryMapper, campaignSendMapper,
                suppressionService, consentService, systemActor, automationExecutor, transactionTemplate);
    }

    private CampaignDelivery delivery(Integer personId) {
        CampaignDelivery delivery = new CampaignDelivery();
        delivery.setId(DELIVERY_ID);
        delivery.setWorkspaceId(WORKSPACE);
        delivery.setSendId(SEND_ID);
        delivery.setAddress("recipient@dest.test");
        delivery.setPersonId(personId);
        return delivery;
    }

    private CampaignSend send() {
        CampaignSend send = new CampaignSend();
        send.setChannel("email");
        send.setPurpose("marketing");
        return send;
    }

    private void scopeInline() {
        User system = new User();
        system.setId(1);
        when(systemActor.user()).thenReturn(system);
        when(automationExecutor.runAs(eq(WORKSPACE), any(), eq("system"), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
    }

    private void transactionInline() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0))
                        .doInTransaction(new SimpleTransactionStatus()));
    }

    @Test
    void preview_readsTheSendAndEventStateInsideTheDeliveryWorkspaceScope() {
        when(campaignDeliveryMapper.getByToken(TOKEN)).thenReturn(delivery(PERSON_ID));
        when(campaignSendMapper.getSend(WORKSPACE, SEND_ID)).thenReturn(send());
        when(campaignDeliveryMapper.hasEvent(WORKSPACE, DELIVERY_ID, "unsubscribed")).thenReturn(false);
        scopeInline();

        DeliveryUnsubscribeDto result = service().preview(TOKEN);

        assertEquals("email", result.channel());
        assertEquals("r***@dest.test", result.address());
        assertFalse(result.unsubscribed());
        InOrder ordered = inOrder(automationExecutor, campaignSendMapper, campaignDeliveryMapper);
        ordered.verify(automationExecutor).runAs(eq(WORKSPACE), any(), eq("system"), any());
        ordered.verify(campaignSendMapper).getSend(WORKSPACE, SEND_ID);
        ordered.verify(campaignDeliveryMapper).hasEvent(WORKSPACE, DELIVERY_ID, "unsubscribed");
    }

    @Test
    void unsubscribe_entersTheWorkspaceScopeBeforeOpeningTheTransaction() {
        when(campaignDeliveryMapper.getByToken(TOKEN)).thenReturn(delivery(PERSON_ID));
        when(campaignSendMapper.getSend(WORKSPACE, SEND_ID)).thenReturn(send());
        scopeInline();
        transactionInline();

        assertTrue(service().unsubscribe(TOKEN).unsubscribed());

        InOrder ordered = inOrder(
                automationExecutor, transactionTemplate, campaignSendMapper, campaignDeliveryMapper);
        ordered.verify(automationExecutor).runAs(eq(WORKSPACE), any(), eq("system"), any());
        ordered.verify(transactionTemplate).execute(any());
        ordered.verify(campaignSendMapper).getSend(WORKSPACE, SEND_ID);
        ordered.verify(campaignDeliveryMapper).insertEvent(any(CampaignDeliveryEvent.class));
    }

    @Test
    void unsubscribe_suppressesRevokesConsentAndRecordsTheEventInTheDeliveryWorkspace() {
        when(campaignDeliveryMapper.getByToken(TOKEN)).thenReturn(delivery(PERSON_ID));
        when(campaignSendMapper.getSend(WORKSPACE, SEND_ID)).thenReturn(send());
        scopeInline();
        transactionInline();

        assertTrue(service().unsubscribe(TOKEN).unsubscribed());

        ArgumentCaptor<SuppressionEntryRequest> suppression =
                ArgumentCaptor.forClass(SuppressionEntryRequest.class);
        verify(suppressionService).add(suppression.capture());
        assertEquals("unsubscribe", suppression.getValue().reason());
        assertEquals("recipient@dest.test", suppression.getValue().address());
        ArgumentCaptor<ContactChannelConsentRequest> consent =
                ArgumentCaptor.forClass(ContactChannelConsentRequest.class);
        verify(consentService).setForPerson(eq(PERSON_ID), consent.capture());
        assertEquals("revoked", consent.getValue().status());
        ArgumentCaptor<CampaignDeliveryEvent> event =
                ArgumentCaptor.forClass(CampaignDeliveryEvent.class);
        verify(campaignDeliveryMapper).insertEvent(event.capture());
        assertEquals(WORKSPACE, event.getValue().getWorkspaceId());
        assertEquals(DELIVERY_ID, event.getValue().getDeliveryId());
        assertEquals("unsubscribed", event.getValue().getEventType());
    }

    @Test
    void unsubscribe_skipsConsentRevocationWhenTheDeliveryHasNoPerson() {
        when(campaignDeliveryMapper.getByToken(TOKEN)).thenReturn(delivery(null));
        when(campaignSendMapper.getSend(WORKSPACE, SEND_ID)).thenReturn(send());
        scopeInline();
        transactionInline();

        assertTrue(service().unsubscribe(TOKEN).unsubscribed());

        verify(suppressionService).add(any(SuppressionEntryRequest.class));
        verifyNoInteractions(consentService);
    }

    @Test
    void unsubscribe_isIdempotentWhenTheDeliveryIsAlreadyUnsubscribed() {
        when(campaignDeliveryMapper.getByToken(TOKEN)).thenReturn(delivery(PERSON_ID));
        when(campaignSendMapper.getSend(WORKSPACE, SEND_ID)).thenReturn(send());
        when(campaignDeliveryMapper.hasEvent(WORKSPACE, DELIVERY_ID, "unsubscribed")).thenReturn(true);
        scopeInline();
        transactionInline();

        assertTrue(service().unsubscribe(TOKEN).unsubscribed());

        verify(campaignDeliveryMapper, never()).insertEvent(any());
        verifyNoInteractions(suppressionService, consentService);
    }

    @Test
    void unsubscribe_rejectsAnUnknownTokenWithoutEnteringAnyWorkspaceScope() {
        when(campaignDeliveryMapper.getByToken(TOKEN)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> service().unsubscribe(TOKEN));

        verifyNoInteractions(automationExecutor, transactionTemplate, campaignSendMapper);
    }

    @Test
    void unsubscribe_rejectsAMissingSendWithoutWriting() {
        when(campaignDeliveryMapper.getByToken(TOKEN)).thenReturn(delivery(PERSON_ID));
        when(campaignSendMapper.getSend(WORKSPACE, SEND_ID)).thenReturn(null);
        scopeInline();
        transactionInline();

        assertThrows(ResourceNotFoundException.class, () -> service().unsubscribe(TOKEN));

        verify(campaignDeliveryMapper, never()).insertEvent(any());
        verifyNoInteractions(suppressionService, consentService);
    }
}
