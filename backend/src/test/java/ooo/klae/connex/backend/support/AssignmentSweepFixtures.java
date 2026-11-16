package ooo.klae.connex.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** Minimal committed or transaction-owned workflow ancestry for an intervention assignment. */
public final class AssignmentSweepFixtures {
    private AssignmentSweepFixtures() {
    }

    public static long intervention(JdbcTemplate jdbc, int workspaceId, int personId, int ownerId) {
        jdbc.update("""
            INSERT INTO workflow (workspace_id, name, draft_execution_mode,
                draft_definition_json, draft_canvas_json)
            VALUES (?, 'assignment sweep', 'system', '{"schemaVersion":1}', '{}')
            """, workspaceId);
        int workflow = jdbc.queryForObject("SELECT MAX(id) FROM workflow WHERE workspace_id = ?",
            Integer.class, workspaceId);
        jdbc.update("""
            INSERT INTO workflow_version (workspace_id, workflow_id, version_number, name,
                record_type, trigger_type, trigger_config, actions_json, execution_mode,
                definition_json, canvas_json, definition_hash)
            VALUES (?, ?, 1, 'assignment sweep', 'person', 'entity_change', '{}', '[]', 'system',
                '{"schemaVersion":1}', '{}', UNHEX(SHA2(UUID(), 256)))
            """, workspaceId, workflow);
        long version = jdbc.queryForObject("SELECT MAX(id) FROM workflow_version WHERE workflow_id = ?",
            Long.class, workflow);
        jdbc.update("""
            INSERT INTO workflow_run (workspace_id, workflow_id, workflow_version_id, status,
                trigger_type, trigger_event, trigger_key, record_type, record_id, dedupe_key,
                execution_mode)
            VALUES (?, ?, ?, 'queued', 'entity_change', 'person.updated', UUID(), 'person', ?, UUID(), 'system')
            """, workspaceId, workflow, version, personId);
        long run = jdbc.queryForObject("SELECT MAX(id) FROM workflow_run WHERE workspace_id = ? AND workflow_id = ?",
            Long.class, workspaceId, workflow);
        jdbc.update("""
            INSERT INTO workflow_intervention (workspace_id, workflow_run_id, intervention_key,
                category, reason_code, owner_user_id, status, resolved_at)
            VALUES (?, ?, UNHEX(SHA2(UUID(), 256)), 'actor', 'actor_unavailable', ?, 'resolved', CURRENT_TIMESTAMP)
            """, workspaceId, run, ownerId);
        return jdbc.queryForObject("SELECT MAX(id) FROM workflow_intervention WHERE workflow_run_id = ?",
            Long.class, run);
    }

    public static void removeWorkflow(JdbcTemplate jdbc, int workspaceId) {
        jdbc.update("DELETE FROM workflow_intervention WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM workflow_run WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM workflow_version WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM workflow WHERE workspace_id = ?", workspaceId);
    }
}
