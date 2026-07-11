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

    /** A single rule scoped to the workspace, or {@code null}. */
    Rule getById(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Inserts a rule, populating its generated id. */
    void insert(Rule rule);

    /** Replaces a rule's mutable fields; returns rows affected. */
    int update(Rule rule);

    /** Deletes a rule scoped to the workspace; returns rows affected. */
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Enabled rules of a trigger type in a workspace (engine dispatch; called off-thread). */
    List<Rule> getEnabledByTrigger(@Param("workspaceId") int workspaceId, @Param("triggerType") String triggerType);

    /** Distinct workspace ids with at least one enabled schedule rule (scheduler fan-out; off-thread, cross-workspace). */
    List<Integer> workspaceIdsWithEnabledScheduleRules();

    /** Claims a fire by inserting it; the unique {@code (rule_id, dedupe_key)} index enforces idempotency. Populates the id. */
    void insertExecution(RuleExecution execution);

    /** Finalizes a claimed execution's outcome. */
    void updateExecution(@Param("workspaceId") int workspaceId, @Param("id") int id, @Param("status") String status, @Param("detail") String detail);

    /** Recent executions for a rule scoped to the workspace, newest first. */
    List<RuleExecution> getExecutionsByRule(@Param("workspaceId") int workspaceId, @Param("ruleId") int ruleId, @Param("limit") int limit);

    /**
     * Nulls the run-as principal on every rule referencing a user. Offboarding
     * replacement for the {@code rule.run_as_user_id} ON DELETE SET NULL (#440
     * increment 3); the rule engine already treats a null run-as as disabled.
     */
    void clearRunAsAnywhere(@Param("userId") int userId);

    /**
     * Nulls the creator reference on every rule created by a user. Offboarding
     * replacement for the {@code rule.created_by_id} ON DELETE SET NULL (#440
     * increment 3).
     */
    void clearCreatedByAnywhere(@Param("userId") int userId);

}
