package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Rule;

/**
 * Data access for automation rules. CRUD statements are workspace-scoped and called on the request
 * thread (guarded by {@code TenantScopeInterceptor}); engine statements added later run off-thread
 * with an explicit workspace id. SQL is in {@code resources/mappers/RuleMapper.xml}.
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
}
