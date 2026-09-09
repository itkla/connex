package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;

/** Pins date occurrence, outbox ownership, source evidence, and teardown ordering. */
class WorkflowDateMigrationArchTest {

    @Test
    void migrationPinsDateOccurrenceAndRunSourceContracts() throws Exception {
        String sql = compact(resource("db/migration/tenant/V208__workflow_date_enrollment.sql"));

        assertTrue(sql.contains("CREATE TABLE workflow_date_enrollment"));
        assertTrue(sql.contains(
            "UNIQUE KEY uq_workflow_date_enrollment_period (workspace_id, workflow_id, "
                + "record_type, record_id, date_field, source_date)"));
        assertTrue(sql.contains(
            "FOREIGN KEY (workspace_id, workflow_date_enrollment_id) "
                + "REFERENCES workflow_date_enrollment(workspace_id, id)"));
        assertTrue(!sql.contains("ADD COLUMN workflow_trigger_outbox_id"));
        assertTrue(sql.contains("ADD INDEX idx_deal_expected_close_date "
            + "(workspace_id, expected_close_date, id)"));
        assertTrue(sql.contains("trigger_type = 'date' AND date_field IS NOT NULL "
            + "AND date_field = 'expectedCloseDate'"));
        assertTrue(sql.contains("trigger_type <> 'date' AND date_field IS NULL"));
        assertTrue(sql.contains(
            "JSON_UNQUOTE(JSON_EXTRACT(draft_definition_json, '$.schemaVersion')) "
                + "IN ('1', '2')"));
    }

    @Test
    void lifecycleDeletesOutboxThenDateEnrollmentThenRun() {
        var outbox = TenantLifecycleRegistry.require("workflow_trigger_outbox");
        var enrollment = TenantLifecycleRegistry.require("workflow_date_enrollment");
        var run = TenantLifecycleRegistry.require("workflow_run");

        assertTrue(outbox.deleteOrder() < enrollment.deleteOrder());
        assertTrue(enrollment.deleteOrder() < run.deleteOrder());
        assertTrue(TablePlaneRegistry.ORG_DATA_TABLES.contains("workflow_date_enrollment"));
    }

    @Test
    void runtimeWorkspaceDiscoveryIncludesDuePlannedOccurrences() throws Exception {
        String mapper = compact(resource("mappers/WorkflowTriggerOutboxMapper.xml"));

        assertTrue(mapper.contains("SELECT workspace_id FROM workflow_runtime_workspace UNION "
            + "SELECT workspace_id FROM workflow_date_enrollment WHERE state = 'planned' "
            + "AND due_at &lt;= CURRENT_TIMESTAMP(6)"));
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = WorkflowDateMigrationArchTest.class
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
