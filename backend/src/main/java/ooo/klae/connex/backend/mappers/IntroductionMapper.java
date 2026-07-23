package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.IntroEmploymentRow;
import ooo.klae.connex.backend.beans.Introduction;
import ooo.klae.connex.backend.beans.WarmPathDismissal;
import ooo.klae.connex.backend.dto.IntroductionDto;

/**
 * Mapper for the {@code introduction} table (reverse-intro lineage and dismissals) and the
 * workspace-scoped projections that feed reverse-introduction candidate ranking.
 * SQL is defined in {@code resources/mappers/IntroductionMapper.xml}.
 */
public interface IntroductionMapper {

    /** Records a made introduction; re-recording an existing pair refreshes it to {@code made}. */
    int recordMade(Introduction introduction);

    /** Dismisses a suggested pair; never downgrades an already-{@code made} pair. */
    int recordDismissed(Introduction introduction);

    /** The lineage feed: made introductions, newest first, with both contacts and the introducer id. */
    List<IntroductionDto> findLineage(@Param("workspaceId") int workspaceId,
            @Param("limit") int limit, @Param("offset") int offset);

    long countLineage(@Param("workspaceId") int workspaceId);

    /** A single made introduction for a pair, or {@code null}; used to return a freshly recorded one. */
    IntroductionDto findByPair(@Param("workspaceId") int workspaceId,
            @Param("personAId") int personAId, @Param("personBId") int personBId);

    /** Workspace-owned contacts the team has engaged, with the attributes ranking/display need. */
    List<IntroCandidatePerson> findCandidatePersons(@Param("workspaceId") int workspaceId);

    List<IntroCandidatePerson> findCandidatePersonsForReport(
            @Param("workspaceId") int workspaceId,
            @Param("limit") int limit);

    /** Every employment record in the workspace, for shared-employer detection. */
    List<IntroEmploymentRow> findWorkspaceEmployment(@Param("workspaceId") int workspaceId);

    List<IntroEmploymentRow> findWorkspaceEmploymentForReport(
            @Param("workspaceId") int workspaceId,
            @Param("personIds") List<Integer> personIds,
            @Param("limit") int limit);

    /** Pairs already recorded (made or dismissed), excluded from fresh suggestions. Only the
     *  {@code personAId}/{@code personBId} of each returned row are populated. */
    List<Introduction> findExistingPairs(@Param("workspaceId") int workspaceId);

    List<Introduction> findExistingPairsForReport(
            @Param("workspaceId") int workspaceId,
            @Param("limit") int limit);

    List<Introduction> findExistingPairsForReverseIntroReport(
            @Param("workspaceId") int workspaceId,
            @Param("personIds") List<Integer> personIds,
            @Param("limit") int limit);

    /**
     * Counts introductions brokered by a user across all workspaces.
     * Service-layer mirror of the {@code introduction.introducer_user_id}
     * ON DELETE RESTRICT (#440 increment 3).
     */
    int countIntroducedAnywhere(@Param("userId") int userId);

    /**
     * Every workspace-owned contact eligible for the warm-path feed — like
     * {@link #findCandidatePersons} but without the engagement gate, so never-contacted imports
     * can surface as reach targets.
     */
    List<IntroCandidatePerson> findWarmPathCandidates(@Param("workspaceId") int workspaceId);

    /** Dismissed/accepted warm paths; a {@code null} bridge covers every path to the target. */
    List<WarmPathDismissal> findWarmPathDismissals(@Param("workspaceId") int workspaceId);

    /** Records a per-bridge warm-path dismissal; re-dismissing an existing pair refreshes it. */
    int recordWarmPathDismissal(
        @Param("workspaceId") int workspaceId,
        @Param("targetPersonId") int targetPersonId,
        @Param("bridgePersonId") int bridgePersonId,
        @Param("status") String status,
        @Param("userId") int userId);

    /** Records a whole-target dismissal, replacing any per-bridge rows for the target. */
    int recordWarmPathTargetDismissal(
        @Param("workspaceId") int workspaceId,
        @Param("targetPersonId") int targetPersonId,
        @Param("status") String status,
        @Param("userId") int userId);

    /** Removes a target's non-accepted dismissal rows before a whole-target dismissal is
     *  recorded; accepted rows are audit lineage and survive. */
    int deleteWarmPathDismissals(
        @Param("workspaceId") int workspaceId,
        @Param("targetPersonId") int targetPersonId);
}
