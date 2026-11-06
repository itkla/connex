package ooo.klae.connex.backend.delivery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;

/** Records a send's content refusal without changing recipient delivery or attempt evidence. */
@Service
@RequiredArgsConstructor
public class CampaignContentRefusalService {
    private final CampaignSendMapper sendMapper;
    private final CampaignDeliveryMapper deliveryMapper;

    /** Serializes one refusal marker per send under its root, without provider I/O or delivery claims. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void record(int workspaceId, int sendId) {
        CampaignSend send = sendMapper.getSendForUpdate(workspaceId, sendId);
        if (send != null && ("queued".equals(send.getStatus()) || "running".equals(send.getStatus())
                || "triggered".equals(send.getStatus()))
                && !deliveryMapper.hasContentRefusal(workspaceId, sendId)) {
            deliveryMapper.insertContentRefusal(workspaceId, sendId);
        }
    }
}
