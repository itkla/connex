package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowListView;

/** Workspace-scoped persistence for mutable workflows and deterministic legacy-rule pairing. */
public interface WorkflowMapper {

    List<Workflow> listByWorkspace(
        @Param("workspaceId") int workspaceId,
        @Param("archived") boolean archived);

    List<WorkflowListView> listItemsByWorkspace(
        @Param("workspaceId") int workspaceId,
        @Param("archived") boolean archived);

    Workflow getById(@Param("workspaceId") int workspaceId, @Param("id") int id);

    Workflow getByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);

    Workflow getByLegacyRuleId(
        @Param("workspaceId") int workspaceId,
        @Param("legacyRuleId") int legacyRuleId);

    Workflow getByLegacyRuleIdForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("legacyRuleId") int legacyRuleId);

    List<Workflow> findAffectedByUserAnywhere(@Param("userId") int userId);

    List<Rule> listUnpairedLegacyRules(@Param("workspaceId") int workspaceId);

    int countLegacyRuleLinks(@Param("workspaceId") int workspaceId);

    int countUnpairedLegacyRules(@Param("workspaceId") int workspaceId);

    Integer firstUnpairedLegacyRuleId(@Param("workspaceId") int workspaceId);

    void insert(Workflow workflow);

    int updateDraft(
        @Param("workflow") Workflow workflow,
        @Param("expectedRevision") int expectedRevision);

    int assignFirstPublication(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("legacyRuleId") int legacyRuleId,
        @Param("activeVersionId") long activeVersionId,
        @Param("updatedById") Integer updatedById,
        @Param("expectedRevision") int expectedRevision);

    int assignFirstCanonicalPublication(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("activeVersionId") long activeVersionId,
        @Param("updatedById") Integer updatedById,
        @Param("expectedRevision") int expectedRevision);

    int advancePublication(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("expectedLegacyRuleId") int expectedLegacyRuleId,
        @Param("expectedActiveVersionId") long expectedActiveVersionId,
        @Param("activeVersionId") long activeVersionId,
        @Param("updatedById") Integer updatedById,
        @Param("expectedRevision") int expectedRevision);

    int advanceCanonicalPublication(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("expectedActiveVersionId") long expectedActiveVersionId,
        @Param("activeVersionId") long activeVersionId,
        @Param("updatedById") Integer updatedById,
        @Param("expectedRevision") int expectedRevision);

    int updateLifecycle(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("enabled") boolean enabled,
        @Param("updatedById") Integer updatedById);

    int replaceLegacyPublication(
        @Param("workflow") Workflow workflow,
        @Param("activeVersionId") long activeVersionId,
        @Param("expectedLegacyRuleId") int expectedLegacyRuleId,
        @Param("expectedActiveVersionId") long expectedActiveVersionId,
        @Param("expectedRevision") int expectedRevision);

    int archive(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("updatedById") int updatedById);

    int restore(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("updatedById") int updatedById);

    int compareAndSwapRuntimeOwner(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("expectedActiveVersionId") long expectedActiveVersionId,
        @Param("expectedOwner") String expectedOwner,
        @Param("runtimeOwner") String runtimeOwner,
        @Param("updatedById") int updatedById);

    int attachLegacyRuleAndCompareAndSwapRuntimeOwner(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("expectedActiveVersionId") long expectedActiveVersionId,
        @Param("legacyRuleId") int legacyRuleId,
        @Param("expectedOwner") String expectedOwner,
        @Param("runtimeOwner") String runtimeOwner,
        @Param("updatedById") int updatedById);

    List<Integer> getEnabledCanonicalIdsByTrigger(
        @Param("workspaceId") int workspaceId,
        @Param("triggerType") String triggerType);

    List<Integer> workspaceIdsWithEnabledScheduleWorkflows();

    int disableForOffboarding(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id);

    int redactUserReferences(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("userId") int userId);

    int updateActiveVersion(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("activeVersionId") Long activeVersionId,
        @Param("updatedById") Integer updatedById);
}
