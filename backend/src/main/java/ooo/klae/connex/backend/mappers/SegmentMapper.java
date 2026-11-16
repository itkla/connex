package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

import ooo.klae.connex.backend.dto.RecordLabelDto;

/**
 * SQL predicates backing the graph-aware smart segments and the rule engine's {@code WHEN}. Every
 * statement is workspace-scoped; the temperature-based predicate ("cooling") is computed in the
 * service via {@code ScoringService} rather than here, since temperature is not persisted. Field
 * conditions run through the dynamic per-record-type {@code *IdsMatching} statements, which whitelist
 * columns and operators as literals and bind every value through {@code #{}}. SQL is in
 * {@code resources/mappers/SegmentMapper.xml}.
 */
public interface SegmentMapper {

    /** Ids of all companies owned by the workspace, used to scope service-computed predicates to owned records. */
    List<Integer> companyIdsInWorkspace(int workspaceId);

    /** Ids of all people owned by the workspace, used as the negate universe for person conditions. */
    List<Integer> personIdsInWorkspace(int workspaceId);

    /** Ids of all people owned by the workspace, including processing-restricted contacts. */
    List<Integer> personIdsInWorkspaceIncludingRestricted(int workspaceId);

    /** Ids of all deals owned by the workspace, used as the negate universe for deal conditions. */
    List<Integer> dealIdsInWorkspace(int workspaceId);

    /** Whether one company, processable person, or deal id belongs to the workspace-owned segment universe. */
    boolean entityIdInWorkspace(@Param("workspaceId") int workspaceId,
            @Param("recordType") String recordType, @Param("entityId") int entityId);

    Integer maximumEntityId(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType);

    List<Integer> entityIdsPage(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType,
        @Param("afterId") int afterId,
        @Param("upperId") int upperId,
        @Param("limit") int limit);

    /** Ids of companies that have at least one open deal ({@code won IS NULL}). */
    List<Integer> companyIdsWithOpenDeal(
            @Param("workspaceId") int workspaceId,
            @Param("candidateIds") List<Integer> candidateIds);

    /**
     * Ids of companies with no processable logged activity in the last {@code days} days.
     * Shared evidence uses the trusted, unfiltered {@code orgWorkspaceIdsJson} ceiling.
     */
    List<Integer> companyIdsNoActivitySince(
            @Param("workspaceId") int workspaceId,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
            @Param("days") int days,
            @Param("candidateIds") List<Integer> candidateIds);

    /**
     * Ids of the companies owning any of {@code personIds}, excluding companies the given user has
     * already logged activity with. {@code personIds} must be non-empty.
     * The unfiltered {@code orgWorkspaceIdsJson} ceiling applies to shared activity evidence;
     * referenced company IDs retain their legacy behavior without a company-visibility join.
     */
    List<Integer> companyIdsForPersonsWithoutUserActivity(@Param("workspaceId") int workspaceId,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
            @Param("userId") int userId, @Param("personIds") List<Integer> personIds,
            @Param("candidateIds") List<Integer> candidateIds);

    /**
     * Candidate-scoped warm-intro predicate for bounded automation evaluation.
     * {@code candidateIds} must be non-empty company ids from one evaluator page.
     * Shared evidence uses the unfiltered {@code orgWorkspaceIdsJson} ceiling. Engaged people retain
     * the legacy archived/suspended filters without a provision-ceased filter.
     */
    List<Integer> companyIdsWithWarmIntro(
            @Param("workspaceId") int workspaceId,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
            @Param("userId") int userId,
            @Param("candidateIds") List<Integer> candidateIds,
            @Param("strongEdge") int strongEdge);

    /**
     * Ids of companies matching one field condition. {@code params} carries {@code workspaceId},
     * {@code field}, {@code op}, and the bound value(s) ({@code value}/{@code pattern}/{@code number}/
     * {@code id}/{@code ids}). The service supplies only whitelisted field/op tokens.
     */
    List<Integer> companyIdsMatching(Map<String, Object> params);

    /**
     * Ids of people matching one field condition; see {@link #companyIdsMatching}.
     * Company conditions also require the trusted, unfiltered {@code orgWorkspaceIdsJson} ceiling.
     */
    List<Integer> personIdsMatching(Map<String, Object> params);

    /**
     * Ids of people matching one field condition, including processing-restricted contacts.
     * Company visibility retains the same {@code orgWorkspaceIdsJson} ceiling as ordinary matches.
     */
    List<Integer> personIdsMatchingIncludingRestricted(Map<String, Object> params);

    /** Ids of deals matching one field condition; see {@link #companyIdsMatching}. */
    List<Integer> dealIdsMatching(Map<String, Object> params);

    /**
     * Ids of companies matching one existence {@code predicate} (whitelisted token in {@code params}).
     * Company supports {@code has_attachment} only; other predicates yield no rows.
     */
    List<Integer> companyExistence(Map<String, Object> params);

    /**
     * Ids of people matching one existence {@code predicate} (whitelisted token): {@code has_open_task},
     * {@code overdue_task}, {@code recent_meeting} ({@code days}-bound), {@code has_note}, or
     * {@code has_attachment}. Excludes processing-restricted contacts unless {@code includeRestrictedPeople}.
     */
    List<Integer> personExistence(Map<String, Object> params);

    /** Ids of deals matching one existence {@code predicate}; see {@link #personExistence}. */
    List<Integer> dealExistence(Map<String, Object> params);

    /** Distinct non-blank industry values in the workspace, for the builder's value picker. */
    List<String> distinctIndustries(int workspaceId);

    /** id + name labels for the given companies, for a preview sample. {@code ids} must be non-empty. */
    List<RecordLabelDto> companyLabels(@Param("workspaceId") int workspaceId, @Param("ids") List<Integer> ids);

    /** id + name labels for the given people, for a preview sample. {@code ids} must be non-empty. */
    List<RecordLabelDto> personLabels(@Param("workspaceId") int workspaceId, @Param("ids") List<Integer> ids);

    /** id + name labels for the given deals, for a preview sample. {@code ids} must be non-empty. */
    List<RecordLabelDto> dealLabels(@Param("workspaceId") int workspaceId, @Param("ids") List<Integer> ids);
}
