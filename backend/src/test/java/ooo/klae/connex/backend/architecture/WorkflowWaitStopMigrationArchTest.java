package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;

/** Pins the durable WAIT, completion evidence, stop, and enrollment schema contract. */
class WorkflowWaitStopMigrationArchTest {

    @Test
    void migrationPinsAppendOnlyCompletionAndCorrelatedWaitEvidence() throws Exception {
        String sql = compact(resource(
            "db/migration/tenant/V207__workflow_wait_stop_and_enrollment.sql"));

        assertTrue(sql.contains("CREATE TABLE task_completion_event"));
        assertTrue(sql.contains(
            "INDEX idx_task_completion_event_task (workspace_id, task_id, occurred_at, id)"));
        assertTrue(!sql.contains("FOREIGN KEY (task_id)"));
        assertTrue(sql.contains("CREATE TABLE workflow_event_wait"));
        assertTrue(sql.contains(
            "UNIQUE KEY uq_workflow_event_wait_node (workspace_id, workflow_run_id, node_id)"));
        assertTrue(sql.contains("resolution IN ('timeout', 'cancelled', 'stopped')"));
        assertTrue(sql.contains("MODIFY COLUMN selected_outcome VARCHAR(16) NULL"));
        assertTrue(sql.contains("selected_outcome IN ('completed', 'timeout')"));
        assertTrue(sql.contains("ADD COLUMN status_reason VARCHAR(64)"));
        assertTrue(sql.contains("'skipped', 'stopped', 'cancelled'"));
    }

    @Test
    void followupMigrationAllowsStoppedEndsWithoutAConfiguredReason() throws Exception {
        String sql = compact(resource(
            "db/migration/tenant/V209__workflow_optional_stop_reason.sql"));

        assertTrue(sql.contains("DROP CHECK chk_workflow_run_status_reason"));
        assertTrue(sql.contains(
            "status_reason IS NULL OR status IN ('skipped', 'stopped')"));
    }

    @Test
    void lifecycleDeletesWaitsBeforeStepsAndKeepsCompletionEvidenceWorkspaceScoped() {
        var wait = TenantLifecycleRegistry.require("workflow_event_wait");
        var step = TenantLifecycleRegistry.require("workflow_step_run");
        var evidence = TenantLifecycleRegistry.require("task_completion_event");
        var task = TenantLifecycleRegistry.require("task");

        assertTrue(wait.deleteOrder() < step.deleteOrder());
        assertTrue(evidence.deleteOrder() < task.deleteOrder());
        assertTrue(evidence.direct());
        assertTrue(evidence.route().workspacePredicate().contains("workspace_id"));
        assertTrue(TablePlaneRegistry.ORG_DATA_TABLES.contains("workflow_event_wait"));
        assertTrue(TablePlaneRegistry.ORG_DATA_TABLES.contains("task_completion_event"));
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = WorkflowWaitStopMigrationArchTest.class
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
