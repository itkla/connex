package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Pins the immutable V143 workflow ownership, archive, and execution-ledger contract. */
class WorkflowRunMigrationArchTest {

    private static final String RESOURCE =
        "db/migration/tenant/V143__workflow_runtime.sql";

    @Test
    void migrationDeclaresTheFrozenRuntimeVocabularyAndOwnershipState() throws Exception {
        String sql = compact();

        assertTrue(sql.contains(
            "CHECK (runtime_owner IN ('legacy', 'canonical'))"));
        assertTrue(sql.contains(
            "'queued', 'running', 'waiting', 'succeeded', 'failed', 'skipped', "
                + "'cancelled', 'intervention_required'"));
        assertTrue(sql.contains(
            "CHECK (trigger_type IN ('entity_change', 'schedule', 'manual'))"));
        assertTrue(sql.contains(
            "'queued', 'running', 'waiting', 'succeeded', 'failed', 'skipped', 'cancelled'"));
        assertFalse(sql.contains("'partial'"));
    }

    @Test
    void migrationPinsVersionHistoryAndCrossWorkspaceIdentity() throws Exception {
        String sql = compact();

        assertTrue(sql.contains(
            "UNIQUE KEY uq_workflow_run_workspace_id (workspace_id, id)"));
        assertTrue(sql.contains(
            "UNIQUE KEY uq_workflow_run_dedupe (workspace_id, workflow_id, dedupe_key)"));
        assertTrue(sql.contains(
            "UNIQUE KEY uq_workflow_step_run_identity "
                + "(workspace_id, workflow_run_id, id)"));
        assertTrue(sql.contains(
            "FOREIGN KEY (workspace_id, workflow_id, workflow_version_id) "
                + "REFERENCES workflow_version(workspace_id, workflow_id, id) ON DELETE RESTRICT"));
        assertTrue(sql.contains(
            "FOREIGN KEY (workspace_id, workflow_run_id) "
                + "REFERENCES workflow_run(workspace_id, id) ON DELETE RESTRICT"));
        assertTrue(sql.contains("current_node_id VARCHAR(64)"));
        assertTrue(sql.contains("record_type VARCHAR(16)"));
        assertTrue(sql.contains("record_id INT NOT NULL"));
    }

    @Test
    void terminalChecksRepresentEveryFrozenStateCoherently() throws Exception {
        String sql = compact();

        assertTrue(sql.contains(
            "status IN ('queued', 'running', 'waiting') AND finished_at IS NULL"));
        assertTrue(sql.contains(
            "status IN ('succeeded', 'skipped', 'cancelled') AND finished_at IS NOT NULL"));
        assertTrue(sql.contains(
            "status IN ('failed', 'intervention_required') AND finished_at IS NOT NULL"));
        assertTrue(sql.contains(
            "status = 'failed' AND finished_at IS NOT NULL AND failure_code IS NOT NULL"));
        assertTrue(sql.contains("CHECK (attempt_count BETWEEN 1 AND 10)"));
        assertTrue(sql.contains("CHECK (sequence_number <= 49)"));
    }

    private static String compact() throws Exception {
        try (InputStream input = WorkflowRunMigrationArchTest.class
                .getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .trim();
        }
    }
}
