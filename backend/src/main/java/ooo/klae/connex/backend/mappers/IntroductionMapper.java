package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.IntroEmploymentRow;
import ooo.klae.connex.backend.beans.Introduction;
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

    /** The lineage feed: made introductions, newest first, with both contacts and the introducer. */
    List<IntroductionDto> findLineage(@Param("workspaceId") int workspaceId,
            @Param("limit") int limit, @Param("offset") int offset);

    long countLineage(@Param("workspaceId") int workspaceId);

    /** A single made introduction for a pair, or {@code null}; used to return a freshly recorded one. */
    IntroductionDto findByPair(@Param("workspaceId") int workspaceId,
            @Param("personAId") int personAId, @Param("personBId") int personBId);

    /** Workspace-owned contacts the team has engaged, with the attributes ranking/display need. */
    List<IntroCandidatePerson> findCandidatePersons(@Param("workspaceId") int workspaceId);

    /** Every employment record in the workspace, for shared-employer detection. */
    List<IntroEmploymentRow> findWorkspaceEmployment(@Param("workspaceId") int workspaceId);

    /** Pairs already recorded (made or dismissed), excluded from fresh suggestions. Only the
     *  {@code personAId}/{@code personBId} of each returned row are populated. */
    List<Introduction> findExistingPairs(@Param("workspaceId") int workspaceId);

    /**
     * Counts introductions brokered by a user across all workspaces.
     * Service-layer mirror of the {@code introduction.introducer_user_id}
     * ON DELETE RESTRICT (#440 increment 3).
     */
    int countIntroducedAnywhere(@Param("userId") int userId);
}
