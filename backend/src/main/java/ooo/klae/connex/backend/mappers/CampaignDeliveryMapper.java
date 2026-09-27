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

    CampaignDelivery getDeliveryForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Returns identity and attempt evidence so restriction and uncertainty checks precede address reads. */
    CampaignDelivery getDeliveryIdentity(@Param("workspaceId") int workspaceId, @Param("id") int id);

    CampaignDelivery getBySendAndPerson(
            @Param("workspaceId") int workspaceId,
            @Param("sendId") int sendId,
            @Param("personId") int personId);

    /** Resolves one delivery by the SHA-256 digest of its unsubscribe token, unrouted. */
    CampaignDelivery getByTokenHash(@Param("tokenHash") String tokenHash);

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

    /** Claims one triggered delivery under an owner-fenced database-clock lease. */
    int claimTriggered(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseMicros") long leaseMicros,
            @Param("providerId") String providerId,
            @Param("attemptTargetFingerprint") String attemptTargetFingerprint);

    /** Marks a recovered pending attempt ambiguous when its exact target is no longer current. */
    int markPendingTriggeredTargetMismatchAmbiguous(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("attemptTargetFingerprint") String attemptTargetFingerprint,
            @Param("lastError") String lastError,
            @Param("lastErrorCode") String lastErrorCode);

    /** Extends a live triggered-delivery claim immediately before provider egress. */
    int renewTriggeredClaim(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseMicros") long leaseMicros);

    /**
     * Releases an owned triggered claim before this attempt's egress, restoring its prior reservation.
     * A null prior reservation clears a clean attempt; a recovered attempt retains older uncertainty.
     * @param workspaceId the owning workspace
     * @param id the delivery
     * @param leaseOwner the still-owning worker
     * @param priorFrequencyReservedAt the reservation read on the claim before this attempt reserved
     * @return one if the owned claim was released
     */
    int releaseTriggeredClaim(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("leaseOwner") String leaseOwner,
            @Param("priorFrequencyReservedAt") LocalDateTime priorFrequencyReservedAt);

    /** Returns one bounded page of expired triggered claims and their exact attempted transports. */
    List<CampaignDelivery> expiredTriggeredClaimsPage(
            @Param("workspaceId") int workspaceId,
            @Param("limit") int limit);

    /**
     * Restores a replay-safe expired claim and persists the deadline-ambiguous marker so later
     * clean retries cannot release evidence of its possible unrecorded submission.
     */
    int recoverExpiredTriggeredClaim(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("attemptTargetFingerprint") String attemptTargetFingerprint);

    /** Marks one expired non-idempotent claim as requiring operator reconciliation. */
    int markExpiredTriggeredClaimAmbiguous(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("lastError") String lastError,
            @Param("lastErrorCode") String lastErrorCode);

    /**
     * Returns one bounded page of unleased audience attempts, with their sends, whose frequency
     * reservation expired more than the grace ago without a submission or terminal write.
     * @param workspaceId the owning workspace
     * @param graceMicros how long past the reservation an attempt must be before it counts as abandoned
     * @param limit the page size
     * @return the abandoned attempts, ordered by id
     */
    List<CampaignDelivery> expiredAudienceReservationsPage(
            @Param("workspaceId") int workspaceId,
            @Param("graceMicros") long graceMicros,
            @Param("limit") int limit);

    /**
     * Marks one abandoned audience attempt as requiring operator reconciliation, keeping its
     * frequency reservation; it never returns the attempt to {@code pending}.
     * @param workspaceId the owning workspace
     * @param id the delivery
     * @param graceMicros the same grace the page was selected with
     * @param lastError the ambiguous failure detail
     * @param lastErrorCode the bounded reason code
     * @return one if the still-abandoned attempt was marked
     */
    int markExpiredAudienceReservationAmbiguous(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("graceMicros") long graceMicros,
            @Param("lastError") String lastError,
            @Param("lastErrorCode") String lastErrorCode);

    /**
     * Records the provider correlation of a submission whose terminal write lost to the audience
     * reservation sweep, on the still-unresolved swept row that carries no provider id yet. It never
     * changes the row's status, reconciliation requirement, or frequency reservation.
     * @param workspaceId the owning workspace
     * @param id the delivery
     * @param providerId the provider that accepted the message
     * @param providerMessageId the provider's message id
     * @param lastError the failure detail the sweep wrote
     * @param lastErrorCode the reason code the sweep wrote
     * @return one if the swept row now carries the correlation
     */
    int attachLateAudienceProviderCorrelation(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("providerId") String providerId,
            @Param("providerMessageId") String providerMessageId,
            @Param("lastError") String lastError,
            @Param("lastErrorCode") String lastErrorCode);

    /**
     * Records the provider message id of a triggered submission whose owner-fenced terminal write
     * lost to the expired-claim sweep, on an unleased row that still names this attempt's target and
     * carries no message id yet. It never changes the row's status, reconciliation state, operator
     * resolution, or lease, and it refuses a row the sweep returned to the queue or a newer attempt
     * has claimed. A row an idempotent replay dispatched is accepted only because that replay's
     * receipt named no message id, so the row settled correlation-free and this submission's id is
     * the only one a bounce or complaint for the deduplicated message can resolve.
     * @param workspaceId the owning workspace
     * @param id the delivery
     * @param providerId the provider that accepted the message, as this attempt's claim recorded it
     * @param attemptTargetFingerprint the target fingerprint this attempt's claim recorded
     * @param providerMessageId the provider's message id
     * @return one if the swept row now carries the correlation
     */
    int attachLateTriggeredProviderCorrelation(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("providerId") String providerId,
            @Param("attemptTargetFingerprint") String attemptTargetFingerprint,
            @Param("providerMessageId") String providerMessageId);

    int markDispatched(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("providerId") String providerId,
            @Param("providerMessageId") String providerMessageId);

    int markTriggeredDispatched(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("leaseOwner") String leaseOwner,
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

    int markTriggeredSkipped(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("leaseOwner") String leaseOwner,
            @Param("skipReason") String skipReason);

    int markFailed(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("lastError") String lastError,
            @Param("lastErrorCode") String lastErrorCode);

    int markTriggeredFailed(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("leaseOwner") String leaseOwner,
            @Param("lastError") String lastError,
            @Param("lastErrorCode") String lastErrorCode);

    /** Persists a terminal ambiguous outcome for a non-leased delivery attempt. */
    int markAmbiguous(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("lastError") String lastError,
            @Param("lastErrorCode") String lastErrorCode);

    /** Persists a terminal ambiguous outcome only for the current triggered-claim owner. */
    int markTriggeredAmbiguous(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("leaseOwner") String leaseOwner,
            @Param("lastError") String lastError,
            @Param("lastErrorCode") String lastErrorCode);

    /** Applies one operator-confirmed terminal outcome to an ambiguous campaign delivery. */
    int resolveReconciliation(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId,
            @Param("id") int id,
            @Param("toStatus") String toStatus,
            @Param("outcome") String outcome,
            @Param("lastError") String lastError);

    /** Counts submissions by their immutable timestamp, independently of events and receipt status. */
    int recentDispatchCount(
            @Param("workspaceId") int workspaceId,
            @Param("personId") int personId,
            @Param("channel") String channel,
            @Param("sendId") int sendId,
            @Param("since") LocalDateTime since);

    /** Counts other deliveries' submissions and unresolved reservations under the workspace mutex. */
    int frequencyConflictCount(
            @Param("workspaceId") int workspaceId,
            @Param("personId") int personId,
            @Param("channel") String channel,
            @Param("deliveryId") int deliveryId,
            @Param("since") LocalDateTime since);

    /** Reserves the frequency window only while this worker still owns a dispatchable claim. */
    int reserveFrequencyWindow(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("personId") int personId,
            @Param("channel") String channel,
            @Param("leaseOwner") String leaseOwner,
            @Param("reservationMicros") long reservationMicros);

    /** Reports whether this worker still holds an unsubmitted dispatchable claim on the delivery. */
    boolean claimStillOwned(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("leaseOwner") String leaseOwner);

    /**
     * Releases a proven pre-egress attempt only when no earlier recovered attempt may have sent.
     * Expired-lease recovery persists {@code deadline_ambiguous} in {@code last_error_code}; clean
     * gate releases persist an explicit pre-egress reason in {@code last_error} with no error code.
     * Retries require that positive clean history, so older unmarked recovered rows remain uncertain.
     * Claims and gate releases retain uncertainty and cannot erase possible submission evidence.
     */
    int releaseFrequencyWindowBeforeEgress(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("leaseOwner") String leaseOwner);

    /** Restores a still-owned retry's non-null prior reservation after proven absence of new egress. */
    int restoreFrequencyWindowBeforeEgress(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("leaseOwner") String leaseOwner,
            @Param("priorFrequencyReservedAt") LocalDateTime priorFrequencyReservedAt);

    void insertEvent(CampaignDeliveryEvent event);

    boolean hasEvent(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("eventType") String eventType);
}
