package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.CampaignSend;

/** Data access for workspace-scoped campaign sends. */
public interface CampaignSendMapper {

    List<CampaignSend> getSendsByCampaign(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId);

    CampaignSend getSend(@Param("workspaceId") int workspaceId, @Param("id") int id);

    CampaignSend getSendForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Returns the long-lived triggered send for one immutable message revision. */
    CampaignSend getTriggeredSend(
            @Param("workspaceId") int workspaceId,
            @Param("messageId") int messageId,
            @Param("messageVersion") int messageVersion);

    void insertSend(CampaignSend send);

    int transitionStatus(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    int markRunning(@Param("workspaceId") int workspaceId, @Param("id") int id);

    int assignProvider(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("providerId") String providerId);

    int markCompleted(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /**
     * Completes a running audience send only while the same statement proves that none of its
     * deliveries is pending or dispatching, so a live worker's terminal write cannot land between the
     * outstanding-work check and the completion.
     * @param workspaceId the owning workspace
     * @param id the send
     * @return one if the send was completed
     */
    int markSettledAudienceSendCompleted(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id);

    int refreshCounters(@Param("workspaceId") int workspaceId, @Param("id") int id);

    List<Integer> queuedSendIds(
            @Param("workspaceId") int workspaceId,
            @Param("triggeredSendEnabled") boolean triggeredSendEnabled);

    /**
     * Returns one bounded page of audience sends that are not yet settled: they are still running
     * with no pending or dispatching delivery. A dispatching delivery may belong to a live worker,
     * whose own settlement completes the send. The predicate depends only on the send and its own
     * deliveries, so a provider webhook or an operator resolution that clears the reconciliation
     * marker cannot strand the send, and a settlement that fails after a recovery sweep is found
     * again on a later pass.
     * @param workspaceId the owning workspace
     * @param limit the page size
     * @return the send ids, ordered by id
     */
    List<Integer> audienceSendsAwaitingRecoverySettlement(
            @Param("workspaceId") int workspaceId,
            @Param("limit") int limit);

    /**
     * Enumerates the pinned catalog's workspaces with dispatch or recovery work: queued or running
     * sends, triggered work, and abandoned audience attempts whose reservation expired past the
     * grace. A stale failed counter is not one of those reasons: the sweep refreshes the counters of
     * the sends it marked in the same pass, and a counter a fault leaves stale is repaired when the
     * operator resolves the reconciliation row that sweep created. The abandoned-attempt arm is
     * driven by the dispatching delivery rows rather than by every audience send, so its cost follows
     * the outstanding recovery work instead of the catalog's send history.
     * @param triggeredSendEnabled whether pending triggered deliveries count as work
     * @param audienceReservationGraceMicros how long past its reservation an audience attempt is abandoned
     * @return the workspace ids
     */
    List<Integer> workspaceIdsWithQueuedSends(
            @Param("triggeredSendEnabled") boolean triggeredSendEnabled,
            @Param("audienceReservationGraceMicros") long audienceReservationGraceMicros);
}
