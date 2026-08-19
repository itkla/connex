package ooo.klae.connex.backend.services;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.dto.CampaignRecipientDto;
import ooo.klae.connex.backend.dto.CampaignRecipientRow;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.RecordLabelDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * The recipients behind a campaign's engagement counts, as a bounded page of contact record links.
 *
 * <p>Each row is one materialized {@code campaign_delivery}, so filtering by delivery status or by a
 * lifecycle event returns exactly the population a counter in
 * {@link CampaignEngagementService} reports: named counts are read from the current delivery status,
 * while {@code unsubscribed} exists only as an event.
 *
 * <p>Authorization mirrors the audience snapshot read rather than the counter read: a delivery is
 * always contact-scoped, so this is a per-person marketing roster and therefore needs both
 * {@link Permission#CAMPAIGN_VIEW} and {@link Permission#CONSENT_MANAGE}, exactly as
 * {@code CampaignService.requireConsentAccess} demands for a person-typed audience.
 */
@Service
@RequiredArgsConstructor
public class CampaignRecipientService {

    /** Delivery statuses a caller may filter by, as the delivery status check constraint defines them. */
    public static final Set<String> RECIPIENT_STATUSES =
            Set.of("pending", "dispatching", "dispatched", "skipped", "failed", "delivered",
                    "bounced", "complained");

    /** Lifecycle events a caller may filter by, as the delivery event check constraint defines them. */
    public static final Set<String> RECIPIENT_EVENTS =
            Set.of("queued", "dispatched", "delivered", "bounced", "complained", "unsubscribed",
                    "failed");

    private final CampaignMapper campaignMapper;
    private final CampaignSendMapper campaignSendMapper;
    private final CampaignDeliveryMapper campaignDeliveryMapper;
    private final SegmentService segmentService;
    private final WorkspaceService workspaceService;

    /**
     * Returns one bounded page of a campaign's recipients.
     *
     * @param campaignId the campaign the deliveries belong to
     * @param sendId one send to restrict to, or null for every send
     * @param statuses validated delivery statuses, or null for every status
     * @param eventType a validated lifecycle event the delivery must carry, or null
     * @param limit the page size
     * @param offset the page offset
     * @return the page of recipients and the total it was drawn from
     */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public PageResponse<CampaignRecipientDto> getRecipients(
            int campaignId,
            Integer sendId,
            List<String> statuses,
            String eventType,
            int limit,
            int offset) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Campaign campaign = campaignMapper.getCampaign(workspaceId, campaignId);
        if (campaign == null) {
            throw new ResourceNotFoundException("Campaign not found with id: " + campaignId);
        }
        workspaceService.requirePermission(Permission.CONSENT_MANAGE);
        if (sendId != null) {
            CampaignSend send = campaignSendMapper.getSend(workspaceId, sendId);
            if (send == null || send.getCampaignId() != campaignId) {
                throw new ResourceNotFoundException("Campaign send not found with id: " + sendId);
            }
        }
        if (eventType != null && !RECIPIENT_EVENTS.contains(eventType)) {
            throw new BadRequestException(
                    "event must be one of: " + String.join(", ", RECIPIENT_EVENTS));
        }
        List<CampaignRecipientRow> rows = campaignDeliveryMapper.listRecipients(
                workspaceId, campaignId, sendId, statuses, eventType, limit, offset);
        long total = campaignDeliveryMapper.countRecipients(
                workspaceId, campaignId, sendId, statuses, eventType);
        return new PageResponse<>(withLabels(rows), total);
    }

    private List<CampaignRecipientDto> withLabels(List<CampaignRecipientRow> rows) {
        List<Integer> personIds = rows.stream()
                .map(CampaignRecipientRow::personId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Integer, String> labels = new HashMap<>();
        for (RecordLabelDto label : segmentService.labels("person", personIds)) {
            labels.put(label.getId(), label.getLabel());
        }
        return rows.stream()
                .map(row -> new CampaignRecipientDto(
                        row.deliveryId(), row.sendId(), row.channel(), row.personId(),
                        row.personId() == null ? null : labels.get(row.personId()),
                        row.status(), row.skipReason(), row.createdAt(), row.updatedAt()))
                .toList();
    }
}
