package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.RuleExecution;

/**
 * Data access for automation rules. CRUD statements are workspace-scoped and called on the request
 * thread (guarded by {@code TenantScopeInterceptor}); the engine statements run off-thread with an
 * explicit workspace id. SQL is in {@code resources/mappers/RuleMapper.xml}.
 */
public interface RuleMapper {

    /** All rules in the workspace, newest first. */
    List<Rule> getByWorkspace(int workspaceId);

    /** All workspace rules locked in stable id order for startup backfill. */
    List<Rule> getByWorkspaceForUpdate(@Param("workspaceId") int workspaceId);

    /** Number of rules in one workspace. */
    int countByWorkspace(@Param("workspaceId") int workspaceId);

    /** A single rule scoped to the workspace, or {@code null}. */
    Rule getById(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** A single workspace-scoped rule locked for lifecycle synchronization, or {@code null}. */
    Rule getByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Exact rule roots affected by permanent account offboarding. */
    List<Rule> findLockCandidatesByUserAnywhere(@Param("userId") int userId);

    /** Inserts a rule, populating its generated id. */
    void insert(Rule rule);

    /** Replaces a rule's mutable fields; returns rows affected. */
    int update(Rule rule);

    /** Synchronizes a rule's enabled state; returns rows affected. */
    int updateEnabled(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("enabled") boolean enabled);

    /** Redacts exact locked rule principal references during permanent account offboarding. */
    int redactUserReferences(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("userId") int userId);

    /** Deletes a rule scoped to the workspace; returns rows affected. */
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Enabled rules of a trigger type in a workspace (engine dispatch; called off-thread). */
    List<Rule> getEnabledByTrigger(@Param("workspaceId") int workspaceId, @Param("triggerType") String triggerType);

    /** Distinct workspace ids with at least one enabled schedule rule (scheduler fan-out; off-thread, cross-workspace). */
    List<Integer> workspaceIdsWithEnabledScheduleRules();

    /** Distinct workspace ids with rules, ordered for catalog-pinned startup fan-out. */
    List<Integer> workspaceIdsWithRules();

    /** Claims a fire by inserting it; the unique {@code (rule_id, dedupe_key)} index enforces idempotency. Populates the id. */
    void insertExecution(RuleExecution execution);

    /** Finalizes a claimed execution's outcome. */
    void updateExecution(@Param("workspaceId") int workspaceId, @Param("id") int id, @Param("status") String status, @Param("detail") String detail);

    /** Recent executions for a rule scoped to the workspace, newest first. */
    List<RuleExecution> getExecutionsByRule(@Param("workspaceId") int workspaceId, @Param("ruleId") int ruleId, @Param("limit") int limit);

}
