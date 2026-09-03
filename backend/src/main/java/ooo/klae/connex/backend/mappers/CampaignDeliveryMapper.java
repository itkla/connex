package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignDeliveryEvent;
import ooo.klae.connex.backend.dto.CampaignRecipientRow;
import ooo.klae.connex.backend.dto.PersonCampaignTouchDto;

/** Data access for materialized campaign deliveries and their append-only events. */
public interface CampaignDeliveryMapper {

    /**
     * One bounded page of the recipients behind a campaign's engagement counts.
     *
     * @param workspaceId the resolved tenant
     * @param campaignId the campaign the deliveries belong to
     * @param sendId one send to restrict to, or null for every send
     * @param statuses the requested delivery statuses, or null for every status
     * @param eventType a lifecycle event the delivery must carry, or null
     * @param limit the page size
     * @param offset the page offset
     * @return the page rows, ordered by delivery id
     */
    List<CampaignRecipientRow> listRecipients(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId,
            @Param("sendId") Integer sendId,
            @Param("statuses") List<String> statuses,
            @Param("eventType") String eventType,
            @Param("limit") int limit,
            @Param("offset") int offset);

    /**
     * The total row count the matching {@link #listRecipients} page is drawn from.
     *
     * @param workspaceId the resolved tenant
     * @param campaignId the campaign the deliveries belong to
     * @param sendId one send to restrict to, or null for every send
     * @param statuses the requested delivery statuses, or null for every status
     * @param eventType a lifecycle event the delivery must carry, or null
     * @return the matching recipient count
     */
    long countRecipients(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId,
            @Param("sendId") Integer sendId,
            @Param("statuses") List<String> statuses,
            @Param("eventType") String eventType);

    /**
     * One bounded page of the campaign touches on a contact's timeline, newest first.
     *
     * @param workspaceId the resolved tenant
     * @param personId the contact record id
     * @param limit the page size
     * @param offset the page offset
     * @return the page rows
     */
    List<PersonCampaignTouchDto> listPersonTouches(
            @Param("workspaceId") int workspaceId,
            @Param("personId") int personId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    /**
     * The total campaign-touch count for one contact.
     *
     * @param workspaceId the resolved tenant
     * @param personId the contact record id
     * @return the matching touch count
     */
    long countPersonTouches(
            @Param("workspaceId") int workspaceId,
            @Param("personId") int personId);

    int insertDeliveries(
            @Param("workspaceId") int workspaceId,
            @Param("deliveries") List<CampaignDelivery> deliveries);

    CampaignDelivery getDelivery(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Returns only identifiers so restriction can be checked before the address column is read. */
    CampaignDelivery getDeliveryIdentity(@Param("workspaceId") int workspaceId, @Param("id") int id);

    CampaignDelivery getBySendAndPerson(
            @Param("workspaceId") int workspaceId,
            @Param("sendId") int sendId,
            @Param("personId") int personId);

    CampaignDelivery getByToken(@Param("token") String token);

    CampaignDelivery findByProviderMessage(
            @Param("workspaceId") int workspaceId,
            @Param("providerId") String providerId,
            @Param("providerMessageId") String providerMessageId);

    List<Integer> pendingDeliveryIds(
            @Param("workspaceId") int workspaceId,
            @Param("sendId") int sendId);

    /** Returns one ordered, bounded page for a worker sweep. */
    List<Integer> pendingDeliveryIdsPage(
            @Param("workspaceId") int workspaceId,
            @Param("sendId") int sendId,
            @Param("limit") int limit);

    int countPending(@Param("workspaceId") int workspaceId, @Param("sendId") int sendId);

    int claim(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Restores a claim when the triggered-send rollout fence closes before provider egress. */
    int releaseClaim(@Param("workspaceId") int workspaceId, @Param("id") int id);

    int markDispatched(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("providerId") String providerId,
            @Param("providerMessageId") String providerMessageId);

    int applyProviderStatus(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("toStatus") String toStatus,
            @Param("fromStatuses") List<String> fromStatuses);

    int markSkipped(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("skipReason") String skipReason);

    int markFailed(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("lastError") String lastError);

    int recentDispatchCount(
            @Param("workspaceId") int workspaceId,
            @Param("personId") int personId,
            @Param("channel") String channel,
            @Param("sendId") int sendId,
            @Param("since") LocalDateTime since);

    void insertEvent(CampaignDeliveryEvent event);

    boolean hasEvent(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("eventType") String eventType);
}
