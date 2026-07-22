package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignDeliveryEvent;

/** Data access for materialized campaign deliveries and their append-only events. */
public interface CampaignDeliveryMapper {

    int insertDeliveries(
            @Param("workspaceId") int workspaceId,
            @Param("deliveries") List<CampaignDelivery> deliveries);

    CampaignDelivery getDelivery(@Param("workspaceId") int workspaceId, @Param("id") int id);

    CampaignDelivery getByToken(@Param("token") String token);

    CampaignDelivery findByProviderMessage(
            @Param("workspaceId") int workspaceId,
            @Param("providerId") String providerId,
            @Param("providerMessageId") String providerMessageId);

    List<Integer> pendingDeliveryIds(
            @Param("workspaceId") int workspaceId,
            @Param("sendId") int sendId);

    int countPending(@Param("workspaceId") int workspaceId, @Param("sendId") int sendId);

    int claim(@Param("workspaceId") int workspaceId, @Param("id") int id);

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
