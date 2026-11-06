package ooo.klae.connex.backend.delivery;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;

class CampaignContentRefusalServiceTest {
    @Test
    void serializesMarkerInsertionUnderTheOwningSendRoot() {
        CampaignSendMapper sends = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveries = mock(CampaignDeliveryMapper.class);
        CampaignSend send = new CampaignSend();
        send.setStatus("queued");
        when(sends.getSendForUpdate(7, 11)).thenReturn(send);
        new CampaignContentRefusalService(sends, deliveries).record(7, 11);
        InOrder order = inOrder(sends, deliveries);
        order.verify(sends).getSendForUpdate(7, 11);
        order.verify(deliveries).hasContentRefusal(7, 11);
        order.verify(deliveries).insertContentRefusal(7, 11);
        order.verifyNoMoreInteractions();
    }

    @Test
    void missingOrOtherWorkspaceSendCannotAcquireARefusalMarker() {
        CampaignSendMapper sends = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveries = mock(CampaignDeliveryMapper.class);
        new CampaignContentRefusalService(sends, deliveries).record(8, 11);
        verifyNoInteractions(deliveries);
    }

    @Test
    void anExistingMarkerPreventsDuplicateInsertionAfterTheMutex() {
        CampaignSendMapper sends = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveries = mock(CampaignDeliveryMapper.class);
        CampaignSend send = new CampaignSend();
        send.setStatus("running");
        when(sends.getSendForUpdate(7, 11)).thenReturn(send);
        when(deliveries.hasContentRefusal(7, 11)).thenReturn(true);
        new CampaignContentRefusalService(sends, deliveries).record(7, 11);
        verify(deliveries, never()).insertContentRefusal(7, 11);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "completed", "draft", "paused"})
    void closedOrPausedSendCannotBeRefusedByAStaleWorker(String status) {
        CampaignSendMapper sends = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveries = mock(CampaignDeliveryMapper.class);
        CampaignSend send = new CampaignSend();
        send.setStatus(status);
        when(sends.getSendForUpdate(7, 11)).thenReturn(send);
        new CampaignContentRefusalService(sends, deliveries).record(7, 11);
        verifyNoInteractions(deliveries);
    }
}
