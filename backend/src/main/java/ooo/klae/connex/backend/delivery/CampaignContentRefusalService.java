package ooo.klae.connex.backend.delivery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;

/** Atomically closes a refused send and its pending work while preserving prior attempt evidence. */
@Service
@RequiredArgsConstructor
public class CampaignContentRefusalService {
    private final CampaignSendMapper sendMapper;
    private final CampaignDeliveryMapper deliveryMapper;

    /** Records refusal and settles only pending deliveries under the owning send mutex. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void record(int workspaceId, int sendId) {
        CampaignSend send = sendMapper.getSendForUpdate(workspaceId, sendId);
        if (send == null || send.getCompletedAt() != null
                || !("queued".equals(send.getStatus()) || "running".equals(send.getStatus())
                || "triggered".equals(send.getStatus()))) {
            return;
        }
        if (!deliveryMapper.hasContentRefusal(workspaceId, sendId)
                && deliveryMapper.insertContentRefusal(workspaceId, sendId) == 0) {
            return;
        }
        deliveryMapper.settleContentRefusedPending(workspaceId, sendId);
        sendMapper.refreshCounters(workspaceId, sendId);
        sendMapper.markContentRefused(workspaceId, sendId);
    }
}
