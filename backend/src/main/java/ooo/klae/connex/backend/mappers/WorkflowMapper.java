package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;

/** Workspace-scoped persistence for mutable workflows and deterministic legacy-rule pairing. */
public interface WorkflowMapper {

    List<Workflow> listByWorkspace(@Param("workspaceId") int workspaceId);

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

    int advancePublication(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("expectedLegacyRuleId") int expectedLegacyRuleId,
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

    int unlinkLegacyRuleForDeletion(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("updatedById") int updatedById,
        @Param("expectedLegacyRuleId") int expectedLegacyRuleId,
        @Param("expectedActiveVersionId") long expectedActiveVersionId,
        @Param("expectedRevision") int expectedRevision);

    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

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
