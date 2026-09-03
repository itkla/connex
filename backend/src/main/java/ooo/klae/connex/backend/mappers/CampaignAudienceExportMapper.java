package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.CampaignAudienceExport;

/**
 * Data access for workspace-scoped campaign audience exports. Every read and write binds an explicit
 * {@code workspaceId}. SQL lives in {@code resources/mappers/CampaignAudienceExportMapper.xml}.
 */
public interface CampaignAudienceExportMapper {

    List<CampaignAudienceExport> getByCampaign(
            @Param("workspaceId") int workspaceId, @Param("campaignId") int campaignId);

    CampaignAudienceExport getExport(@Param("workspaceId") int workspaceId, @Param("id") int id);

    CampaignAudienceExport getExportForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /**
     * Tests the rollback-compatible duplicate fence. Every running row is active, including
     * reconciliation-required and legacy null-lease rows.
     * @param workspaceId workspace scope
     * @param campaignId owning campaign
     * @param snapshotId immutable audience snapshot
     * @param connector connector id
     * @return whether an active or potentially delivered export blocks replacement
     */
    boolean existsActiveForSnapshotConnector(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId,
            @Param("snapshotId") int snapshotId,
            @Param("connector") String connector);

    /**
     * Flags only running rows with an expired nonnull lease for reconciliation and classifies their
     * outcome as ambiguous in the same write. Their stored status remains rollback-compatible
     * {@code running}; legacy null-lease rows remain unflagged because their in-flight lifetime is
     * unknown.
     * @param workspaceId workspace scope
     * @param campaignId owning campaign
     * @param exportIds candidate export ids
     * @return the number of expired rows transitioned
     */
    int markStaleRunningNeedsReconciliation(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId,
            @Param("exportIds") List<Integer> exportIds);

    int nextAttemptForSnapshotTarget(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId,
            @Param("snapshotId") int snapshotId,
            @Param("connector") String connector,
            @Param("externalListId") String externalListId);

    /**
     * Inserts an export and derives any running lease exclusively from the database clock.
     * @param export export row
     * @param leaseMicros lease duration in microseconds
     */
    void insertExport(
            @Param("export") CampaignAudienceExport export,
            @Param("leaseMicros") long leaseMicros);

    /**
     * Stages the exact request identities and refreshes the lease from the database clock.
     * @param export staged export fields
     * @param leaseMicros lease duration in microseconds
     * @return one when the running unflagged row was staged, otherwise zero
     */
    int stagePush(
            @Param("export") CampaignAudienceExport export,
            @Param("leaseMicros") long leaseMicros);

    /**
     * Persists an outcome and its bounded classification only for the same running, unflagged
     * attempt and idempotency key.
     * @param export workspace-scoped attempted outcome
     * @return one when the attempt was completed, otherwise zero
     */
    int updateOutcome(CampaignAudienceExport export);

    /**
     * Marks a provider outcome that arrived after another actor transitioned the export.
     * @param workspaceId workspace scope
     * @param id export id
     * @param lateOutcome bounded provider outcome code
     * @param failureReason bounded definitive failure code, or null
     * @return one when the history marker was persisted, otherwise zero
     */
    int markLateOutcome(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("lateOutcome") String lateOutcome,
            @Param("failureReason") String failureReason);

    /**
     * Applies an operator-confirmed terminal outcome and classification to a flagged export or to a
     * legacy in-flight export whose null lease prevents automatic stale classification.
     * @param export workspace-scoped terminal outcome
     * @return one when the eligible source state was transitioned, otherwise zero
     */
    int resolveReconciliation(CampaignAudienceExport export);
}
