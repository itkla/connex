package ooo.klae.connex.backend.mappers;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import ooo.klae.connex.backend.beans.OwnershipOffer;

/** Control-plane ownership consent; mutations require the documented scope roots first. */
public interface OwnershipOfferMapper {
    /** Inserts a seven-day offer using the UTC database clock. */
    int insert(OwnershipOffer offer);
    /** Discovers one offer without locks. */
    OwnershipOffer findById(@Param("id") long id);
    /** Locks the exact offer after its account and scope roots. */
    OwnershipOffer lockById(@Param("id") long id);
    /** Retains delivery's shared offer lock through its notification write. */
    OwnershipOffer lockByIdForShare(@Param("id") long id);
    /** Locks the unique open slot after the scope roots. */
    OwnershipOffer lockOpen(@Param("scopeKind") String scopeKind, @Param("scopeId") int scopeId,
        @Param("offereeUserId") int offereeUserId);
    /** Lists unexpired open offers for the recipient, before live eligibility filtering. */
    List<OwnershipOffer> listForOfferee(@Param("userId") int userId);
    /** Lists unexpired open offers in a scope, before live eligibility filtering. */
    List<OwnershipOffer> listForScope(@Param("scopeKind") String scopeKind, @Param("scopeId") int scopeId);
    /** Discovers open scope offers without locking rows before their roots. */
    List<OwnershipOffer> discoverScope(@Param("orgId") int orgId, @Param("workspaceId") Integer workspaceId);
    /** Discovers both party references and nullable closure attribution for erasure. */
    List<OwnershipOffer> discoverAccount(@Param("userId") int userId);
    /** Discovers a bounded organization-fence page for individual scope transactions. */
    List<OwnershipOffer> fencedOrganizationOffers(@Param("orgId") int orgId, @Param("limit") int limit);

    /** Captures the fixed maximum id for a disposable reconciliation traversal. */
    Long findExpiryWatermark();
    /** Discovers expiry and lifecycle candidates after the cursor through the fixed watermark. */
    List<OwnershipOffer> expiredCandidates(@Param("cursor") long cursor, @Param("watermark") long watermark,
        @Param("limit") int limit);
    /** Discovers a bounded delivery reconciliation pass. */
    List<OwnershipOffer> deliveryCandidates(@Param("limit") int limit);
    /** Claims OPEN consent with the database-clock expiry predicate. */
    int accept(@Param("id") long id, @Param("offereeUserId") int offereeUserId);
    /** Closes an OPEN offer; the caller resolves its notice before committing. */
    int close(@Param("id") long id, @Param("state") String state,
        @Param("actorId") Integer actorId, @Param("reason") String reason);
    /** Tests expiry from the UTC database clock, without application-clock skew. */
    boolean isExpired(@Param("id") long id);
    /** Finds an active destination membership without creating one. */
    Integer findDeliveryWorkspace(@Param("orgId") int orgId, @Param("offereeUserId") int offereeUserId);
    /** Marks a completed delivery while its shared offer lock is retained. */
    int markDelivered(@Param("id") long id, @Param("workspaceId") int workspaceId,
        @Param("membershipId") long membershipId);
    /** Clears third-party attribution explicitly before account erasure audit heads. */
    int clearClosureActor(@Param("id") long id, @Param("userId") int userId);
    /** Deletes an exact, already-closed party reference after ordered roots. */
    int deletePartyReference(@Param("id") long id, @Param("userId") int userId);
    /** Tests organization eligibility after excluding a membership about to depart. */
    boolean hasOtherQualifyingTenancy(@Param("orgId") int orgId, @Param("userId") int userId,
        @Param("excludedWorkspaceId") int excludedWorkspaceId);

    /** Discovers all scope and destination references before lifecycle cleanup locks them individually. */
    List<OwnershipOffer> workspaceReferences(@Param("workspaceId") int workspaceId);
    /** Clears destination attribution before workspace deletion and before audit heads. */
    int clearDeliveryWorkspace(@Param("id") long id, @Param("workspaceId") int workspaceId);
    /** Counts workspace scope references for post-root residual verification. */
    long countWorkspaceReferences(@Param("workspaceId") int workspaceId);

    /** Distinguishes a live control root from its post-deletion cleanup tombstone. */
    boolean workspaceRootExists(@Param("workspaceId") int workspaceId);

    /** Advances existing delivered-notice state without creating a recipient-account FK reference. */
    int bumpExistingNoticeStateVersion(@Param("recipientId") int recipientId);

}
