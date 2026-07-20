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

    List<Rule> listUnpairedLegacyRules(@Param("workspaceId") int workspaceId);

    void insert(Workflow workflow);

    int updateDraft(Workflow workflow);

    int updateLifecycle(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("enabled") boolean enabled,
        @Param("updatedById") Integer updatedById);

    int updateActiveVersion(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("activeVersionId") Long activeVersionId,
        @Param("updatedById") Integer updatedById);
}
