package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SQL predicates backing the graph-aware smart segments (company scope).
 * Every statement is workspace-scoped; the temperature-based predicate ("cooling")
 * is computed in the service via {@code ScoringService} rather than here, since
 * temperature is not persisted. SQL is in {@code resources/mappers/SegmentMapper.xml}.
 */
public interface SegmentMapper {

    /** Ids of all companies owned by the workspace, used to scope service-computed predicates to owned records. */
    List<Integer> companyIdsInWorkspace(int workspaceId);

    /** Ids of companies that have at least one open deal ({@code won IS NULL}). */
    List<Integer> companyIdsWithOpenDeal(int workspaceId);

    /** Ids of companies with no logged activity (via their people or deals) in the last {@code days} days. */
    List<Integer> companyIdsNoActivitySince(@Param("workspaceId") int workspaceId, @Param("days") int days);

    /**
     * Ids of the companies owning any of {@code personIds}, excluding companies the given user has
     * already logged activity with. {@code personIds} must be non-empty.
     */
    List<Integer> companyIdsForPersonsWithoutUserActivity(@Param("workspaceId") int workspaceId,
            @Param("userId") int userId, @Param("personIds") List<Integer> personIds);

    /** Ids of companies whose industry exactly equals {@code industry}. */
    List<Integer> companyIdsByIndustry(@Param("workspaceId") int workspaceId, @Param("industry") String industry);

    /** Ids of companies whose industry matches the (already LIKE-escaped) {@code pattern}. */
    List<Integer> companyIdsByIndustryContains(@Param("workspaceId") int workspaceId, @Param("pattern") String pattern);

    /** Ids of companies whose name matches the (already LIKE-escaped) {@code pattern}. */
    List<Integer> companyIdsByNameContains(@Param("workspaceId") int workspaceId, @Param("pattern") String pattern);

    /** Ids of companies tagged with {@code tagId}. */
    List<Integer> companyIdsByTag(@Param("workspaceId") int workspaceId, @Param("tagId") int tagId);

    /** Distinct non-blank industry values in the workspace, for the builder's value picker. */
    List<String> distinctIndustries(int workspaceId);
}
