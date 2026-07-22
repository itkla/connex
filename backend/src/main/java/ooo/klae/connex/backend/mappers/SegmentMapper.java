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

    /** Ids of companies that have at least one open deal ({@code won IS NULL}). */
    List<Integer> companyIdsWithOpenDeal(int workspaceId);

    /** Ids of companies with no processable logged activity in the last {@code days} days. */
    List<Integer> companyIdsNoActivitySince(@Param("workspaceId") int workspaceId, @Param("days") int days);

    /**
     * Ids of the companies owning any of {@code personIds}, excluding companies the given user has
     * already logged activity with. {@code personIds} must be non-empty.
     */
    List<Integer> companyIdsForPersonsWithoutUserActivity(@Param("workspaceId") int workspaceId,
            @Param("userId") int userId, @Param("personIds") List<Integer> personIds);

    /**
     * Ids of companies matching one field condition. {@code params} carries {@code workspaceId},
     * {@code field}, {@code op}, and the bound value(s) ({@code value}/{@code pattern}/{@code number}/
     * {@code id}/{@code ids}). The service supplies only whitelisted field/op tokens.
     */
    List<Integer> companyIdsMatching(Map<String, Object> params);

    /** Ids of people matching one field condition; see {@link #companyIdsMatching}. */
    List<Integer> personIdsMatching(Map<String, Object> params);

    /** Ids of people matching one field condition, including processing-restricted contacts. */
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
