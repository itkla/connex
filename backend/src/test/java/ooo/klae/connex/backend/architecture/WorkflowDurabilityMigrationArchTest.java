package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Pins the V144 outbox, lease, wait, cancellation, and attempt contract. */
class WorkflowDurabilityMigrationArchTest {

    private static final String MIGRATION =
        "db/migration/tenant/V144__workflow_durability.sql";

    @Test
    void durabilitySchemaKeepsFrozenKeysAndRestrictiveHistory() throws Exception {
        String sql = compact(resource(MIGRATION));

        assertTrue(sql.contains("CREATE TABLE workflow_runtime_workspace"));
        assertTrue(sql.contains("CREATE TABLE workflow_trigger_outbox"));
        assertTrue(sql.contains("CREATE TABLE workflow_step_attempt"));
        assertTrue(sql.contains("ADD COLUMN runtime_generation BIGINT UNSIGNED"));
        assertTrue(sql.contains("ADD COLUMN wait_kind VARCHAR(8)"));
        assertTrue(sql.contains("ADD COLUMN resume_at DATETIME(6)"));
        assertTrue(sql.contains("ADD COLUMN lease_owner CHAR(36)"));
        assertTrue(sql.contains("ADD COLUMN cancel_requested_at DATETIME(6)"));
        assertTrue(sql.contains(
            "FOREIGN KEY (workspace_id, workflow_id, workflow_version_id) "
                + "REFERENCES workflow_version(workspace_id, workflow_id, id) "
                + "ON DELETE RESTRICT"));
        assertTrue(sql.contains(
            "FOREIGN KEY (workspace_id, workflow_run_id, workflow_step_run_id) "
                + "REFERENCES workflow_step_run(workspace_id, workflow_run_id, id) "
                + "ON DELETE RESTRICT"));
        assertTrue(sql.contains(
            "UNIQUE KEY uq_workflow_trigger_outbox_dedupe "
                + "(workspace_id, workflow_id, dedupe_key)"));
        assertTrue(sql.contains(
            "UNIQUE KEY uq_workflow_step_attempt_number "
                + "(workspace_id, workflow_run_id, workflow_step_run_id, attempt_number)"));
        assertTrue(sql.contains("CHECK (attempt_number BETWEEN 1 AND 3)"));
        assertTrue(sql.contains(
            "UPDATE workflow_run SET status = 'intervention_required'"));
        assertTrue(sql.contains(
            "finished_at = GREATEST(started_at, CURRENT_TIMESTAMP(6))"));
        assertTrue(sql.contains(
            "wsr.finished_at = GREATEST(wsr.started_at, CURRENT_TIMESTAMP(6))"));
        assertTrue(sql.indexOf("UPDATE workflow_run SET status = 'intervention_required'")
            < sql.indexOf("ADD CONSTRAINT chk_workflow_run_runtime_state"));
        assertFalse(sql.contains("chk_workflow_step_run_runtime_attempts"));
        assertFalse(sql.contains("trigger_dedupe_key"));
        assertFalse(sql.contains("ON DELETE CASCADE"));
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = WorkflowDurabilityMigrationArchTest.class
                .getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Migration resource is unavailable");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
